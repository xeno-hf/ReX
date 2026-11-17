package rx.protocol

/**
 * 讀寫 ECU 物件字典的介面。XB 的 EcuLink（自己開 TCP 線）和 ReX 的 Session（共用監控那條線）都實作它，
 * 調校層（xb-link 的 EcuSync／LiveTuning）只認這個介面，兩邊用同一份寫入流程。
 */
interface SdoPort {
    /** 讀一個物件。[block]＝true 走區塊讀（XTune 讀表的方式）。ECU abort 丟 [SdoAbortException]，逾時丟 [IOException]。 */
    suspend fun read(index: Int, sub: Int, block: Boolean = true): ByteArray
    /** 寫一個物件：4 bytes 以內 expedited，其餘區塊寫入（[SdoDownload]）。不自動讀回。 */
    suspend fun write(index: Int, sub: Int, data: ByteArray)
}

class SdoAbortException(val abort: Sdo.Abort) : IOException(abort.toString())
