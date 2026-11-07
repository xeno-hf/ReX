package rx.defs

import kotlin.math.max
import kotlin.math.min

/**
 * 單一即時通道定義。
 *
 * Vinf 欄位：[0]absid [1]cellSize [2]scale 除數 [3]offset 減項 [4]max [5]min
 * [6]type(Para/Byte/BitN) [7]unit [8]stage 或分類 [9]decimals [13]顯示別名（例 TPS_Percent→"TPS%"）
 * 官方換算：`physical = clamp((raw − offset) ÷ scale, min, max)`
 */
data class ChannelDef(
    val name: String,
    val absid: Int,
    val cellSize: Int,
    val scale: Double,
    val offset: Double,
    val max: Double,
    val min: Double,
    val type: String,
    val unit: String,
    val group: String,
    val decimals: Int?,
    /** c_version → 是否啟用（韌體版本相依）。 */
    val versions: Map<Int, Boolean>,
    /** Vinf[13]，XTune ``；儀表格標題優先用它。 */
    val alias: String? = null,
) {
    /** 儀表上顯示的名字（XTune Gauge_Text/Gauge_Bar 同規則：有別名用別名）。 */
    val label: String get() = alias ?: name

    val bitIndex: Int? = BIT_RE.matchEntire(type)?.groupValues?.get(1)?.toInt()
    val isBit: Boolean get() = bitIndex != null
    /** Byte 型＝位元容器（Malf/Mode 族），本身以整數呈現。 */
    val isBitContainer: Boolean get() = type == "Byte"

    /** loga／App 內部用的 VarID：值通道＝absid，位元＝−(absid×10)−bit。 */
    val logVarId: Int get() = bitIndex?.let { -(absid * 10) - it } ?: absid
    /** 線上（頻道表）VarID＝absid + 16384；位元通道不能直接上表。 */
    val wireVarId: Int get() = absid + 16384

    /** raw → 物理值。位元通道請傳「母通道 raw」。 */
    fun physical(raw: Long): Double {
        bitIndex?.let { return ((raw ushr it) and 1L).toDouble() }
        if (scale == 0.0) return raw.toDouble()
        val v = (raw - offset) / scale
        return min(max(v, this.min), this.max)
    }

    fun enabledFor(cVersion: Int): Boolean = versions[cVersion] ?: false

    private companion object {
        val BIT_RE = Regex("Bit(\\d)")
    }
}

class ChannelRegistry(val serial: String?, val channels: List<ChannelDef>) {

    /** 值通道（可上頻道表的母通道），以 absid 索引。 */
    val values: Map<Int, ChannelDef> = channels.filter { !it.isBit }.associateBy { it.absid }

    /** 母通道 absid → 其位元子通道。 */
    val bitsOf: Map<Int, List<ChannelDef>> = channels.filter { it.isBit }.groupBy { it.absid }

    val byLogVarId: Map<Int, ChannelDef> = channels.associateBy { it.logVarId }
    val byName: Map<String, ChannelDef> = channels.associateBy { it.name }

    /** 精簡快取：一行一條通道（tab 分隔），啟動時讀它，不必每次重解 7 MB XML。 */
    fun toCompact(): String = buildString {
        append(COMPACT_MAGIC).append('\t').append(serial ?: "").append('\n')
        for (c in channels) {
            listOf(
                c.name, c.absid, c.cellSize, c.scale, c.offset, c.max, c.min, c.type, c.unit, c.group,
                c.decimals ?: "", c.alias ?: "", c.versions.entries.joinToString(";") { "${it.key}:${if (it.value) 1 else 0}" },
            ).joinTo(this, "\t")
            append('\n')
        }
    }

    companion object {
        private const val COMPACT_MAGIC = "RXDEF1"
        private val VINF_RE = Regex("<Vinf>([^<]*)</Vinf>")
        private val VER_RE = Regex("<Ver c_version=\"(\\d+)\" status=\"(\\w+)\"")
        private val SERIAL_RE = Regex("<ControlFileSerial sn=\"([^\"]+)\"")

        fun fromCompact(text: String): ChannelRegistry {
            val lines = text.split('\n')
            val head = lines.first().split('\t')
            require(head[0] == COMPACT_MAGIC) { "cache format mismatch" }
            val out = ArrayList<ChannelDef>(lines.size)
            for (k in 1 until lines.size) {
                val l = lines[k]
                if (l.isEmpty()) continue
                val f = l.split('\t')
                out += ChannelDef(
                    name = f[0], absid = f[1].toInt(), cellSize = f[2].toInt(), scale = f[3].toDouble(), offset = f[4].toDouble(),
                    max = f[5].toDouble(), min = f[6].toDouble(), type = f[7], unit = f[8], group = f[9],
                    decimals = f[10].toIntOrNull(), alias = f[11].ifEmpty { null },
                    versions = if (f[12].isEmpty()) emptyMap() else f[12].split(';').associate { v ->
                        v.substringBefore(':').toInt() to (v.substringAfter(':') == "1")
                    },
                )
            }
            return ChannelRegistry(head.getOrNull(1)?.ifEmpty { null }, out)
        }

        /** 解析 ControlFile XML。用 indexOf 逐段切，比整段 regex 快很多。 */
        fun parse(xml: String): ChannelRegistry {
            val s0 = xml.indexOf("<Variables>")
            val s1 = xml.lastIndexOf("</Variables>")
            require(s0 >= 0 && s1 > s0) { "<Variables> section not found" }
            val out = ArrayList<ChannelDef>(4096)
            var p = s0
            while (true) {
                val a = xml.indexOf("<Var name=\"", p)
                if (a < 0 || a > s1) break
                val nameEnd = xml.indexOf('"', a + 11)
                val name = xml.substring(a + 11, nameEnd)
                val idStart = xml.indexOf("absid=\"", nameEnd) + 7
                val absidStr = xml.substring(idStart, xml.indexOf('"', idStart))
                val bodyStart = xml.indexOf('>', idStart) + 1
                val bodyEnd = xml.indexOf("</Var>", bodyStart)
                p = bodyEnd + 6
                val body = xml.substring(bodyStart, bodyEnd)
                val vinf = VINF_RE.find(body)?.groupValues?.get(1) ?: continue
                val f = vinf.split(',').map { it.trim() }
                fun s(i: Int) = f.getOrNull(i)?.takeIf { it != "_" } ?: ""
                fun d(i: Int, def: Double) = s(i).toDoubleOrNull() ?: def
                out += ChannelDef(
                    name = name,
                    absid = absidStr.trim().toInt(),
                    cellSize = s(1).toIntOrNull() ?: 1,
                    scale = d(2, 1.0),
                    offset = d(3, 0.0),
                    max = d(4, Double.MAX_VALUE),
                    min = d(5, -Double.MAX_VALUE),
                    type = s(6),
                    unit = s(7),
                    group = s(8),
                    decimals = s(9).toIntOrNull(),
                    versions = VER_RE.findAll(body).associate { it.groupValues[1].toInt() to (it.groupValues[2] == "Enable") },
                    alias = s(13).ifEmpty { null },
                )
            }
            return ChannelRegistry(SERIAL_RE.find(xml)?.groupValues?.get(1), out)
        }
    }
}
