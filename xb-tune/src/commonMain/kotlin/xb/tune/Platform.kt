package xb.tune

/**
 * ECU 平台：調校檔類型對應哪份定義檔、哪組名稱。
 * 對照是調校檔檔頭的 MAP_Type 與定義檔的命名。
 */
enum class Platform(val display: String, val mapTypes: List<String>, val definition: String, val labelSet: String) {
    S3("Mini X / Super X", listOf("AMX", "ASX"), "S3", "XECU"),
    X2("X2", listOf("AMX2", "ASX2"), "X2", "XECU"),
    CYL4("Super X 4-cylinder", listOf("A4X"), "CYL4", "SC4ECU"),
    RZR("Super X RZR", listOf("AZR"), "RZR", "RZRECU"),
    SXS("SXS", listOf("SXS"), "SXS", "SXS"),
    MGU_SYM("MGU SYM", listOf("AMG"), "SYMGU", "SYMGU"),
    UMA_M9("UMA M9", listOf("AM6"), "UMA_M9", "XECU");

    companion object {
        fun ofMapType(mapType: String): Platform? = entries.firstOrNull { mapType.trim() in it.mapTypes }

        /** 附掛的 MGU 表用的定義檔與名稱。 */
        const val ATTACHED_DEFINITION = "SYMGU"
        const val ATTACHED_LABEL_SET = "SYMGU"

        /**
         * 群組在清單裡的順序：燃油相關在前、診斷與系統在後。
         * 是 STX 倒過來；沒列到的群組排在最後。
         */
        val GROUP_ORDER: List<String> = listOf(
            "Fuel_Cal", "Warm_Up_Cal", "Acc_Fuel", "Target_AFR", "Closed_Loop_Fuel", "Dec_Fuel", "Start_Cal",
            "Dec_Fuel_Cut", "Inj_Angle", "Transient_Fuel", "Fuel_Sys", "Fuel_FE", "SA_Cal", "RPM_Limiter",
            "P_Atm_Cal", "MGU_Cal", "Gear_Settings", "QS_Settings", "Engine_Brake", "TCS_Cal", "Launch_Control",
            "Pit_Limiter", "Race_Sys_Setting", "Dual_Injection", "NOS_Settings", "Drag_Settings", "Mode_Cal",
            "BackFire", "Rollover_Switch", "Sensor_Cal", "Inlet_Air_Cal", "TPS_Sys_Cal", "VVA_Cal", "E_suspension",
            "MiniTune_Adjust", "APP_Adjust", "4C_Adjust", "iMode_Adjust", "Kncok_Cal", "Sequential_Cal", "EEPROM",
            "Sys_Cal", "Others", "Diag_Cal",
        )
    }
}
