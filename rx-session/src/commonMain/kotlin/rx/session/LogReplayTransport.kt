package rx.session

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import rx.defs.ChannelRegistry
import rx.defs.Loga
import rx.protocol.CanId
import rx.protocol.Frame
import rx.protocol.FrameParser
import rx.protocol.Rpc
import rx.protocol.Sdo
import kotlin.concurrent.Volatile
import kotlin.math.roundToLong

/** 給離線重播 用的通道值來源：absid＋cell → raw。 */
fun interface ValueSource {
    fun raw(absid: Int, cellSize: Int, tMs: Long): Long
}

/** Offline recording playback through the common Transport interface. Values are not live telemetry. */
class LogReplayTransport(
    private val scope: CoroutineScope,
    private val values: ValueSource,
    private val roundHz: Double = 16.0,
    private val password: Int = 888888,
    var asleep: Boolean = false,
    private val crankResetAtMs: List<Long> = emptyList(),
    private val resetClearsTable: Boolean = false,
    private val nowMs: () -> Long = { rx.protocol.monotonicNanos() / 1_000_000 },
    private val deviceType: Int = 1,
    /** Identifier for offline playback. */
    private val serial: ByteArray = "REX-REPLAY".encodeToByteArray(),
    private val hardwareId: Int = 0,
    private val code8: ByteArray = byteArrayOf(0x52, 0x45, 0x58, 0x50, 0x4C, 0x41, 0x59, 0x31),
    /** 0x6A1 第一位元組：7＝鍵盤鎖（需登入）。 */
    private val keypadState: Int = 7,
    /** 醒著但沒人寫表時的出廠廣播（實車＝19 片預設表；內容未全解，這裡用幾個錨點通道代替）。null＝不廣播。 */
    private val factoryTable: List<Pair<Int, Int>>? = null,
    /** IO Config 讀回。null＝不回應。 */
    private val ioConfig: ByteArray? = null,
) : Transport {
    private val out = Channel<ByteArray>(Channel.UNLIMITED)
    private val parser = FrameParser(::onFrame)
    private val tableSlots = HashMap<Int, Long>()
    @Volatile private var count = 0
    @Volatile private var running = false
    private val start = nowMs()
    private val jobs = ArrayList<Job>()
    var framesReceived = 0L; private set
    val writes = ArrayList<Triple<Int, Int, Long>>()

    init {
        jobs += scope.launch {
            while (true) {
                emit(Frame(CanId.HEARTBEAT_BRIDGE, byteArrayOf(5, 0, 0, 0, 0, 0, 0, 0)))
                emit(Frame(CanId.HEARTBEAT_APP, byteArrayOf(5, 0, 0, 0, 0, 0, 0, 0)))
                delay(1000)
            }
        }
        jobs += scope.launch {
            val period = (1000.0 / roundHz).roundToLong()
            while (true) {
                delay(period)
                if ((running || (count <= 0 && factoryTable != null)) && !asleep) broadcastRound()
            }
        }
        for (at in crankResetAtMs) jobs += scope.launch {
            delay(at)
            running = false
            if (resetClearsTable) { tableSlots.clear(); count = 0 }
        }
    }

    private fun emit(f: Frame) { out.trySend(f.encode()) }

    override suspend fun send(bytes: ByteArray) = parser.feed(bytes)
    override suspend fun receive(): ByteArray? = out.receiveCatching().getOrNull()
    override fun close() { jobs.forEach { it.cancel() }; out.close() }

    private fun entries(): List<Pair<Int, Int>>? {
        if (count <= 0) return factoryTable
        return (1..count).map { i ->
            val u = tableSlots[i] ?: return null
            val cell = ((u and 0xFF) shr 3).toInt()
            val absid = ((u ushr 16) and 0xFFFF).toInt() - 16384
            absid to cell
        }
    }

    private fun broadcastRound() {
        val es = entries() ?: return
        val t = nowMs() - start
        val payload = rx.protocol.ByteSink()
        for ((absid, cell) in es) {
            val v = values.raw(absid, cell, t)
            for (k in cell - 1 downTo 0) payload.write(((v ushr (8 * k)) and 0xFF).toInt())
        }
        val p = payload.toByteArray()
        val n = (p.size + 6) / 7
        for (i in 0 until n) {
            val chunk = p.copyOfRange(i * 7, minOf(p.size, i * 7 + 7))
            val head = (i + 1) or (if (i == n - 1) 0x80 else 0)
            emit(Frame(CanId.BROADCAST, byteArrayOf(head.toByte()) + chunk))
        }
    }

    /**
     * 物件字典（block upload 讀得到的項目）。0x1008＝裝置名稱，實車回 10 bytes（內容未錄）；
     * 其餘是測試用假資料：一張短表、一張跨多個 block 的長表。
     */
    val od = HashMap<Pair<Int, Int>, ByteArray>().apply {
        put(0x1008 to 0, "REX-REPLAY\u0000".encodeToByteArray())
        // Neutral playback metadata; not copied from a vehicle.
        fun le(v: Long, n: Int) = ByteArray(n) { (v ushr (8 * it)).toByte() }
        for (k in listOf(2, 5, 6, 7, 8, 9, 11, 12, 13, 14, 15, 17, 18)) put(0x1018 to k, le(0, 4))
        put(0x1018 to 10, code8.copyOf())
        put(0x3000 + 1230 to 0, ByteArray(24) { (it * 3 + 1).toByte() })
        put(0x3000 + 1431 to 0, ByteArray(1431 * 2) { (it * 7 xor (it ushr 8)).toByte() })
    }
    /** 實車對 0x1008 等小讀回一般錯誤；只對 0x1018/0x200A/0x200B 族給 expedited（XTune 會讀）。 */
    private fun expeditedOk(index: Int) = index == 0x1018 || index == 0x200A || index == 0x200B

    /** 重播 CRC 支援（實車 0x1008 回應 cc=0）。 */
    var odCrc = false

    private class BlockUp(val data: ByteArray, val blksize: Int, var pos: Int = 0, var crc: Boolean)
    private var blockUp: BlockUp? = null

    private fun onBlockUpload(f: Frame, d0: Int, addr: Int, sub: Int) {
        val reply = CanId.rpcReply(deviceType)
        fun send(vararg b: Int) = emit(Frame(reply, ByteArray(8).also { a -> b.forEachIndexed { i, v -> a[i] = v.toByte() } }))
        when {
            d0 == 0x80 -> blockUp = null
            d0 == 0xA0 || d0 == 0xA4 -> {
                val data = od[addr to sub] ?: run { emit(Sdo.abortFrame(reply, addr, sub, 0x06020000L)); return }
                val crc = odCrc && d0 == 0xA4
                blockUp = BlockUp(data, f.u8(4).coerceIn(1, 127), crc = crc)
                val n = data.size
                send(0xC2 or (if (crc) 4 else 0), addr and 0xFF, addr ushr 8, sub, n and 0xFF, (n ushr 8) and 0xFF, (n ushr 16) and 0xFF, n ushr 24)
            }
            d0 == 0xA3 || d0 == 0xA2 -> {
                val b = blockUp ?: return
                if (d0 == 0xA2 && b.pos >= b.data.size) {
                    val nUnused = (7 - b.data.size % 7) % 7
                    val c = if (b.crc) Sdo.crc16(b.data) else 0
                    send(0xC1 or (nUnused shl 2), c and 0xFF, c ushr 8); return
                }
                for (seq in 1..b.blksize) {
                    val chunk = b.data.copyOfRange(b.pos, minOf(b.data.size, b.pos + 7))
                    b.pos += chunk.size
                    val last = b.pos >= b.data.size
                    emit(Frame(reply, byteArrayOf((seq or if (last) 0x80 else 0).toByte()) + chunk + ByteArray(7 - chunk.size)))
                    if (last) break
                }
            }
            d0 == 0xA1 -> blockUp = null
        }
    }

    /**
     * IO Config 讀回：3C → {3D,00,_,_,lenBE16,sum16BE}；3E seq → 每 64B 一塊：
     * {3F,01,seq,塊數(×64B),4B} 起，之後 {3F,n,6B}… 直到該塊 64B 收滿。
     */
    private fun onIoConfig(f: Frame) {
        val io = ioConfig ?: return
        val sum = io.fold(0) { a, b -> (a + (b.toInt() and 0xFF)) and 0xFFFF }
        if (f.u8(0) == 0x3C) {
            emit(Frame(CanId.SYS + 0x20, byteArrayOf(0x3D, 0, 0, 0, (io.size ushr 8).toByte(), io.size.toByte(), (sum ushr 8).toByte(), sum.toByte())))
            return
        }
        val seq = if (f.dlc >= 2) f.u8(1) else 0
        val block = ByteArray(64).also { b -> io.copyInto(b, 0, minOf(io.size, seq * 64), minOf(io.size, seq * 64 + 64)) }
        emit(Frame(CanId.SYS + 0x20, byteArrayOf(0x3F, 1, seq.toByte(), 1) + block.copyOfRange(0, 4)))
        var pos = 4; var n = 2
        while (pos < 64) {
            val k = minOf(6, 64 - pos)
            emit(Frame(CanId.SYS + 0x20, byteArrayOf(0x3F, n.toByte()) + block.copyOfRange(pos, pos + k)))
            pos += k; n++
        }
    }

    /** TabReadAll 用的校正區內容；null＝依請求長度回全 0（只為了讓流程走下去）。 */
    var calibrationBlob: ByteArray? = null
    private var tabBlob: ByteArray? = null

    /**
     * TabReadAll：App `{38, C_BE16, 總長_BE16}` →
     * `{39,00,_,_,_,_,sum16BE}`；App `{3A,seq}` → 64B 一塊，`{3B,01,seq,1,4B}`＋`{3B,n,6B}…`（同 IO Config）。
     */
    private fun onTabReadAll(f: Frame) {
        val reply = CanId.SYS + 0x20
        if (f.u8(0) == 0x38) {
            val total = (f.u8(3) shl 8) or f.u8(4)
            val blob = calibrationBlob?.copyOf(total) ?: ByteArray(total)
            tabBlob = blob
            val sum = blob.fold(0) { a, b -> (a + (b.toInt() and 0xFF)) and 0xFFFF }
            emit(Frame(reply, byteArrayOf(0x39, 0, 0, 0, 0, 0, (sum ushr 8).toByte(), sum.toByte())))
            return
        }
        val blob = tabBlob ?: return
        val seq = if (f.dlc >= 2) f.u8(1) else 0
        val block = ByteArray(64).also { b -> if (seq * 64 < blob.size) blob.copyInto(b, 0, seq * 64, minOf(blob.size, seq * 64 + 64)) }
        emit(Frame(reply, byteArrayOf(0x3B, 1, seq.toByte(), 1) + block.copyOfRange(0, 4)))
        var pos = 4; var n = 2
        while (pos < 64) { val k = minOf(6, 64 - pos); emit(Frame(reply, byteArrayOf(0x3B, n.toByte()) + block.copyOfRange(pos, pos + k))); pos += k; n++ }
    }

    /** 重播 2026-10-16 ISS 停車時的卡死：RPC／登入全不回（心跳照常）。 */
    @Volatile var rpcSilent = false

    private fun onFrame(f: Frame) {
        framesReceived++
        if (rpcSilent && f.canId != CanId.HEARTBEAT_APP) return
        when (f.canId) {
            CanId.rpc(deviceType) -> {
                val d0 = f.u8(0)
                val addr = f.u8(1) or (f.u8(2) shl 8)
                val sub = f.u8(3)
                if (Sdo.ccs(d0) == Sdo.CCS_BLOCK_UPLOAD || (d0 == 0x80 && blockUp != null)) { onBlockUpload(f, d0, addr, sub); return }
                if (d0 == 0x40) {
                    val v = od[addr to sub]
                    if (v != null && v.size <= 4 && expeditedOk(addr)) {
                        val d = ByteArray(8); d[0] = (0x43 or ((4 - v.size) shl 2)).toByte()
                        d[1] = addr.toByte(); d[2] = (addr ushr 8).toByte(); d[3] = sub.toByte(); v.copyInto(d, 4)
                        emit(Frame(CanId.rpcReply(deviceType), d)); return
                    }
                    val code = if (v != null) 0x08000000L else 0x06020000L
                    emit(Sdo.abortFrame(CanId.rpcReply(deviceType), addr, sub, code)); return
                }
                if (d0 and 3 == 3 && f.dlc == 8) {
                    val n = 4 - ((d0 shr 2) and 3)
                    var v = 0L
                    for (k in 0 until n) v = v or (f.u8(4 + k).toLong() shl (8 * k))
                    writes += Triple(addr, sub, v)
                    when {
                        addr == Rpc.MON_ADDR && sub == Rpc.MON_SUB -> running = v == Rpc.MON_RUN
                        addr == Rpc.CHAN_TABLE_ADDR && sub == 0 -> count = v.toInt()
                        addr == Rpc.CHAN_TABLE_ADDR -> tableSlots[sub] = v
                    }
                    emit(Frame(CanId.rpcReply(deviceType), byteArrayOf(0x60, addr.toByte(), (addr ushr 8).toByte(), sub.toByte(), 0, 0, 0, 0)))
                } else {
                    emit(Frame(CanId.rpcReply(deviceType), byteArrayOf(0x80.toByte(), 0, 0, 0, 0, 0, 0, 0x08)))
                }
            }
            CanId.SESSION -> emit(Frame(0x6A1, byteArrayOf(keypadState.toByte(), 0)))
            CanId.HANDSHAKE -> when (f.u8(0)) {
                0xD1 -> listOf(0xD2 to 0, 0xD3 to 7, 0xD4 to 14).forEach { (op, at) ->
                    val part = serial.copyOfRange(minOf(at, serial.size), minOf(serial.size, at + 7))
                    emit(Frame(0x6A2, byteArrayOf(op.toByte()) + part))
                }
                0xC1 -> emit(Frame(0x6A2, byteArrayOf(0xC2.toByte(), (hardwareId ushr 24).toByte(), (hardwareId ushr 16).toByte(), (hardwareId ushr 8).toByte(), hardwareId.toByte())))
                0xB1 -> {
                    emit(Frame(0x6A2, byteArrayOf(0xB2.toByte()) + code8.copyOfRange(0, 7)))
                    emit(Frame(0x6A2, byteArrayOf(0xB3.toByte(), code8[7])))
                }
                else -> Unit
            }
            CanId.SYS -> if (f.dlc >= 1 && (f.u8(0) == 0x3C || f.u8(0) == 0x3E) && ioConfig != null) {
                onIoConfig(f)
            } else if (f.dlc >= 1 && (f.u8(0) == 0x38 || f.u8(0) == 0x3A)) {
                onTabReadAll(f)
            } else if (f.dlc == 5 && f.u8(0) == 0x88) {
                val pw = (f.u8(2) shl 16) or (f.u8(3) shl 8) or f.u8(4)
                emit(Frame(CanId.LOGIN_REPLY, byteArrayOf(0x89.toByte(), if (pw == password) 0 else 1)))
            }
            else -> Unit
        }
    }
}

/**
 * 用一份真實 loga 當離線重播 的值來源（物理值 → 官方公式反推 raw；位元容器由位元欄組回）。
 * 依 Time 欄循環播放。
 */
class LogaValueSource(
    private val loga: Loga,
    private val registry: ChannelRegistry,
    /** loga 的 Eng_AP 欄是否為 psi（XTune 預設單位設定）；null＝自動判斷（怠速 AP 不可能 < 40 kPa）。 */
    psi: Boolean? = null,
) : ValueSource {
    private companion object { const val PSI_TO_KPA = 6.894757 }
    private val psiColumns: Boolean = psi ?: loga.names.indexOf("Cyl1_Eng_AP").let { j ->
        j >= 0 && loga.rows.maxOf { it[j] } < 40.0
    }
    private val time = loga.column("Time")
    private val durationMs = (time.last() - time.first()).toLong().coerceAtLeast(100)
    private val colByVarId = loga.varIds.withIndex().associate { (i, id) -> id to i }
    private var cursor = 0

    private fun rowAt(tMs: Long): Int {
        val target = time.first() + (tMs % durationMs)
        if (target < time[cursor]) cursor = 0
        while (cursor + 1 < time.size && time[cursor + 1] <= target) cursor++
        return cursor
    }

    override fun raw(absid: Int, cellSize: Int, tMs: Long): Long {
        val row = loga.rows[rowAt(tMs)]
        val def = registry.values[absid] ?: return 0
        val maxRaw = (1L shl (8 * cellSize)) - 1
        if (def.isBitContainer) {
            var v = 0L
            registry.bitsOf[absid].orEmpty().forEach { b ->
                val j = colByVarId[b.logVarId] ?: return@forEach
                if (row[j] > 0.5) v = v or (1L shl b.bitIndex!!)
            }
            return v
        }
        val j = colByVarId[def.absid]
        var phys = if (j != null) row[j] else 0.0
        // XTune 匯出 loga 時依 App 單位設定把 Eng_AP 族換成 psi；還原成 ECU 的 kPa
        if (psiColumns && def.unit == "kPa" && "Eng_AP" in def.name) phys *= PSI_TO_KPA
        return (phys * def.scale + def.offset).roundToLong().coerceIn(0, maxRaw)
    }
}
