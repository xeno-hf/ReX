package xb.tune

/**
 * 功能專頁：群組、項目、用哪一種頁、哪幾張表、哪些機型才有。
 * XB 桌面版與 ReX 共用。盤點與條件出處見。
 * 機型用 ECU 的 CANopen node（1 Mini X、2 Super X、33 Mini XX、34 Super XX、37 SXS）。
 * 授權（XTune 的鎖頭）不在這裡判斷：沒授權的表 ECU 會拒絕寫（abort 0x06010004），編輯器照樣鎖住，不繞過。
 */
object CalItems {
    enum class Group(val key: String) { Fuel("Cal_Fuel"), Spark("Cal_Spark"), Engine("Cal_Engine"), Vehicle("Cal_Vehicle"), Accessory("Cal_Accessory"), SxS("Cal_SxS") }

    /** 頁的種類（XTune 的 nav_* 頁）。[generic]＝通用頁（單值或油圖）；其他是專屬頁。[grid]＝油圖頁（表格編輯器加分頁）。 */
    enum class Page(val generic: Boolean, val grid: Boolean = false) {
        D1(true), D1x4(true), D1x5(true), D3(true, true), SxsD3(true, true),
        D3InjAngle(false), Backfire(false), EBoost(false), EBoostX2(false), AutoWarmup(false), NbClosedLoop(false),
        IdleAdj(false), TpsLearn(false), InjectorCal(false), QsSetting(false), QsWizard(false), RButton(false),
        ResButton(false), RgbLed(false), WindShield(false), Exup(false), ECvtAt(false), ECvtMt(false),
        AutoTune(false), AutoTunePro(false), Service(false), SxsBoost(false),
    }

    class Item(val key: String, val group: Group, val page: Page, val tables: List<Int>)

    /** 沒連 ECU 時，用調校檔類型（MAP_Type）推機型 node；推不出來（四缸、RZR、MGU…）回 null。 */
    fun nodeOfMapType(mapType: String): Int? = when (mapType.trim()) {
        "AMX" -> 1; "ASX" -> 2; "AMX2" -> 33; "ASX2" -> 34; "SXS" -> 37; else -> null
    }

    private fun mini(n: Int) = n == 1 || n == 33
    private fun superX(n: Int) = n == 2 || n == 34
    private fun x2(n: Int) = n == 33 || n == 34 || n == 37

    /**
     * 這台 ECU 的選單（照 XTune 的順序）。[model]＝Table ID 第三段的車型（X2 的 eCVT 看 61）；[tableId] 是完整車種代碼。
     * 跟授權、韌體版本有關的顯示條件（AF2 加熱延遲要韌體版 ≥4、即時馬力 ≥6、eBoost 看車種旗標）沒做：表在不在由呼叫的人用調校包判斷。
     */
    fun items(node: Int, model0: Int? = null, tableId: String? = null): List<Item> {
        val model = model0?.takeIf { it != 0 } // 0＝調校檔沒有 Table ID
        val out = ArrayList<Item>()
        fun add(key: String, g: Group, p: Page, vararg t: Int) { out += Item(key, g, p, t.toList()) }
        // 1 燃油
        add("CalItem_FuelBase", Group.Fuel, Page.D1, 19)
        add("CalItem_FuelAdj", Group.Fuel, Page.D3, 4)
        add("CalItem_FuelAcc", Group.Fuel, Page.D3, 21)
        add("CalItem_InjAngle", Group.Fuel, if (x2(node)) Page.D3InjAngle else Page.D3, 61)
        add("CalItem_TempComp", Group.Fuel, Page.D3, 41)
        add("CalItem_FuelComp", Group.Fuel, Page.D1x4, 1005, 1006, 1007)
        add("CalItem_TagAFR", Group.Fuel, Page.D3, 54, 966, 933)
        add("CalItem_DFCO", Group.Fuel, Page.D1, 22)
        if (x2(node)) add("CalItem_InjCal", Group.Fuel, Page.InjectorCal, 72)
        // 2 點火
        add("CalItem_SparkAngle", Group.Spark, Page.D3, 133, 967, 150)
        add("CalItem_BackFire", Group.Spark, Page.Backfire)
        // 3 引擎
        add("CalItem_IdleRPM", Group.Engine, Page.D1, 20)
        add("CalItem_IdleAdj", Group.Engine, Page.IdleAdj)
        add("CalItem_RPMLimit", Group.Engine, Page.D1, 8)
        if (superX(node) || mini(node)) add("CalItem_VVA", Group.Engine, Page.D1, 37)
        if (node == 34 || node == 37) {
            add("CalItem_AutoTunePro", Group.Engine, Page.AutoTunePro, 14)
            add("CalItem_WBCLC", Group.Engine, Page.AutoTunePro, 14)
        } else {
            add("CalItem_AutoTune", Group.Engine, Page.AutoTune, 14)
            add("CalItem_WBCLC", Group.Engine, Page.D1, 1059)
        }
        val noNb = tableId != null && mini(node) && listOf("02_35_", "02_29_", "02_43_", "02_46_", "02_40_", "02_45_").any { tableId.contains(it) }
        if (!noNb) add("CalItem_NBCLC", Group.Engine, Page.NbClosedLoop, 60, 842)
        add("CalItem_PowerEstimation", Group.Engine, Page.D3, 1309)
        add("CalItem_HighIdle", Group.Engine, Page.D1x4, 1311, 1294)
        // 5 車輛（XTune 的群組代號 5 排在第 4 個）
        add("CalItem_SpeedGain", Group.Vehicle, Page.D1, 33)
        add("CalItem_Fan", Group.Vehicle, Page.D1, 28)
        add("CalItem_Launch", Group.Vehicle, Page.D1x4, 1150, 6, 11, 123)
        add("CalItem_PitLimit", Group.Vehicle, Page.D1x4, 1010, 7, 131)
        add("CalItem_TPSLearn", Group.Vehicle, Page.TpsLearn)
        add("CalItem_ResButton", Group.Vehicle, Page.ResButton, 1046, 1047, 1049, 1050, 1051, 1052, 1053, 1054, 1056)
        add("CalItem_TSC", Group.Vehicle, Page.D1x5, 1067, 1127, 138, 1128, 136)
        if (superX(node) || mini(node)) {
            add("CalItem_TSC_Plus", Group.Vehicle, Page.D1x4, 1408, 136)
            add("CalItem_Maintenance", Group.Vehicle, Page.Service, 8207)
        }
        add("CalItem_AutoWarmup", Group.Vehicle, Page.AutoWarmup, 1065)
        // eCVT 只有車型 61（Table ID 第三段）；Super XX 連線時不列手排
        if (x2(node) && (model == null || model == 61)) {
            add("CalItem_eCVT", Group.Vehicle, Page.ECvtAt, 1553, 1537, 1538, 1501, 1533, 1535, 1534, 1536, 1578)
            if (model == null || node != 34) add("CalItem_eCVT_MT", Group.Vehicle, Page.ECvtMt, 1375, 1376, 1377, 1378, 1379, 1380)
        }
        if (x2(node)) add("CalItem_eBoost", Group.Vehicle, Page.EBoostX2, 1230, 1202, 1418, 1391, 1566, 1567, 1568)
        else if (superX(node) || mini(node)) add("CalItem_eBoost", Group.Vehicle, Page.EBoost, 1230, 1202, 1418, 1391)
        if (x2(node) && (tableId == null || tableId.startsWith("01_76"))) add("CalItem_WindShield", Group.Vehicle, Page.WindShield, 1009, 1133)
        // 4 配件
        if (node != 5) {
            add("CalItem_QSWizard", Group.Accessory, Page.QsWizard, 29, 9, 10)
            add("CalItem_QSSetting", Group.Accessory, Page.QsSetting, 29, 9, 10)
            add("CalItem_QSCutTime", Group.Accessory, Page.D3, 1132)
        }
        add("CalItem_AF2Heater", Group.Accessory, Page.D1, 1283)
        if (superX(node) || mini(node)) add("CalItem_EXUP", Group.Accessory, Page.Exup, 1401, 1402, 1403, 1405)
        add("CalItem_RButton", Group.Accessory, Page.RButton, 1388, 1387, 1389, 1390)
        if (x2(node)) add("CalItem_RGBLED", Group.Accessory, Page.RgbLed)
        // 6 SxS
        if (node == 37) {
            add("CalItem_Knock", Group.SxS, Page.SxsD3, 1501, 1513, 1571, 1573, 1497, 1512, 1570, 1572, 1496, 1613, 977, 1358, 1359)
            add("CalItem_EtcSetting", Group.SxS, Page.SxsD3, 1299, 1300, 1301, 1302, 1303, 1304, 1525, 1526)
            add("CalItem_Boost", Group.SxS, Page.SxsBoost, 1463, 1514, 1515)
        }
        return out
    }

    /** 功能名稱（四語；用跟官方選單相同的功能名詞，方便對照）。[lang]：en-US、zh-TW、zh-CN、ja-JP。 */
    fun name(key: String, lang: String): String {
        val a = NAMES[key] ?: return key
        return when (lang) { "zh-TW" -> a[1]; "zh-CN" -> a[2]; "ja-JP" -> a[3]; else -> a[0] }
    }

    private val NAMES: Map<String, Array<String>> = mapOf(
        "CalItem_AF2Heater" to arrayOf("AF2 Heater Delay", "AF2加熱延遲", "AF2加热延迟", "AF2ヒーター遅延"),
        "CalItem_AutoTune" to arrayOf("Auto Tune", "自動調校", "自动调校", "自動調整"),
        "CalItem_AutoTunePro" to arrayOf("Auto Tune Pro", "超級自動調校", "超级自动调校", "スーパーオートチューニング"),
        "CalItem_AutoWarmup" to arrayOf("Auto Warm-up", "自動暖車", "自动暖车", "自動ウォームアップ"),
        "CalItem_BackFire" to arrayOf("BackFire", "回火設定", "回火设置", "バックファイア設定"),
        "CalItem_Boost" to arrayOf("Turbo Control", "渦輪控制", "涡轮控制", "ターボ制御"),
        "CalItem_DFCO" to arrayOf("Dec. Fuel Cut", "減速斷油", "减速断油", "ディセラションカット"),
        "CalItem_DualInj" to arrayOf("Dual Injector", "雙噴嘴設定", "双喷嘴设置", "デュアルインジェクター設定"),
        "CalItem_EXUP" to arrayOf("EXUP", "EXUP排氣動力閥門", "EXUP排气动力阀门", "EXUP排気パワーバルブ"),
        "CalItem_EXUP_D3MapSku" to arrayOf("Enable D3 MAP Auto Control", "開啟 D3 MAP 閥門控制", "打开 D3 MAP 阀门控制", "有効化 D3 MAP バルブ制御"),
        "CalItem_EtcSetting" to arrayOf("ETC Control", "ETC控制", "ETC控制", "ETC制御"),
        "CalItem_Fan" to arrayOf("Fan", "風扇溫度", "风扇温度", "ファン温度"),
        "CalItem_FuelAcc" to arrayOf("Fuel Acc.", "加速燃油", "加速燃油", "アクセル燃料"),
        "CalItem_FuelAdj" to arrayOf("Fuel Adj.", "燃油調整", "燃油调整", "燃料調整"),
        "CalItem_FuelBase" to arrayOf("Fuel Base", "基礎燃油", "基础燃油", "基本燃料"),
        "CalItem_FuelComp" to arrayOf("Crank Comp.", "啟動補償", "启动补偿", "起動補償"),
        "CalItem_HighIdle" to arrayOf("High Idle", "高怠速模式", "高怠速模式", "高アイドリングモード"),
        "CalItem_HighIdleSku" to arrayOf("Off Road & Urban High Idle Mode", "越野 & 塞車高怠速模式", "越野 & 塞车高怠速模式", "オフロード＆渋滞用高アイドリングモード"),
        "CalItem_IdleAdj" to arrayOf("Idle Adj.", "怠速設定", "怠速设置", "アイドリング調整"),
        "CalItem_IdleRPM" to arrayOf("Idle RPM", "怠速轉速", "怠速转速", "アイドリング回転数"),
        "CalItem_InjAngle" to arrayOf("Inj. Angle", "噴油正時", "喷油正时", "インジェクター角度"),
        "CalItem_InjCal" to arrayOf("Inj. Cal.", "噴嘴校正", "喷嘴校正", "インジェクター校正"),
        "CalItem_Knock" to arrayOf("Knock Control", "爆震控制", "爆震控制", "ノック制御"),
        "CalItem_Launch" to arrayOf("Launch Control", "起跑模式", "起跑模式", "ランチコントロール"),
        "CalItem_Maintenance" to arrayOf("Service Alert", "保養服務", "保养服务", "メンテナンス"),
        "CalItem_NBCLC" to arrayOf("NB. O2 CL.", "窄域閉迴路", "窄域闭回路", "ナローバンドクローズループ"),
        "CalItem_PitLimit" to arrayOf("Pit Limit", "Pit限速", "Pit限速", "ピット制限"),
        "CalItem_PowerEstimation" to arrayOf("Instant Horsepower", "即時馬力", "实时马力", "即時パワー"),
        "CalItem_PowerSku" to arrayOf("Real Time Power & Torque Display", "動力即時顯示", "动力实时显示", "パワー即時表示"),
        "CalItem_QSCutTime" to arrayOf("QS Cut Time", "快排動力切斷時間", "快排动力切断时间", "QS カットオフタイム"),
        "CalItem_QSSetting" to arrayOf("QS Setting", "快排設定", "快排设置", "QS設定"),
        "CalItem_QSWizard" to arrayOf("QS Setup Wizard", "快排設定精靈", "快排设置精灵", "QS 設定ウィザード"),
        "CalItem_RButton" to arrayOf("R.Button Func.", "R.Button設定", "R.Button设置", "R.Button設定"),
        "CalItem_RGBLED" to arrayOf("ECU LED", "ECU燈效設定", "ECU灯效设置", "ECUライト効果"),
        "CalItem_RPMLimit" to arrayOf("RPM Limit", "轉速限制", "转速限制", "回転数制限"),
        "CalItem_ResButton" to arrayOf("Ride Mode Matrix", "騎乘動力陣列", "骑乘动力数组", "ライディングパワーアレイ"),
        "CalItem_SparkAngle" to arrayOf("Spark Angle", "點火角度", "点火角度", "点火角度"),
        "CalItem_SpeedGain" to arrayOf("Speed Gain", "車速調整", "车速调整", "車速調整"),
        "CalItem_TPSLearn" to arrayOf("TPS Lean", "TPS 學習", "TPS 学习", "TPS学習"),
        "CalItem_TSC" to arrayOf("Traction Control", "循跡設定", "循迹设置", "TSC 設定"),
        "CalItem_TSC_Plus" to arrayOf("TCS Plus", "循跡設定Plus", "循迹设置Plus", "TCS Plus"),
        "CalItem_TagAFR" to arrayOf("Target AFR", "目標空燃比", "目标空燃比", "目標エアフューエル比"),
        "CalItem_TempComp" to arrayOf("Temp. Comp.", "引擎溫度補償", "引擎温度补偿", "エンジン温度補償"),
        "CalItem_VVA" to arrayOf("VVA", "VVA控制", "VVA控制", "VVA制御"),
        "CalItem_WBCLC" to arrayOf("WB. O2 CL.", "寬域閉迴路", "宽域闭回路", "ワイドバンドクローズループ"),
        "CalItem_WindShield" to arrayOf("Auto Windshield", "自動風鏡", "自动风镜", "オートウインドシールド"),
        "CalItem_eBoost" to arrayOf("eBoost", "eBoost", "eBoost", "eBoost"),
        "CalItem_eCVT" to arrayOf("eCVT", "eCVT", "eCVT", "eCVT"),
        "CalItem_eCVT_MT" to arrayOf("eCVT MT", "eCVT MT", "eCVT MT", "eCVT MT"),
        "Cal_Accessory" to arrayOf("Accessory settings", "配件功能設定", "配件功能设置", "アクセサリー設定"),
        "Cal_Engine" to arrayOf("Engine", "引擎設定", "引擎设置", "エンジン設定"),
        "Cal_Fuel" to arrayOf("Fuel", "燃油設定", "燃油设置", "燃料設定"),
        "Cal_Spark" to arrayOf("Spark", "點火設定", "点火设置", "点火設定"),
        "Cal_SxS" to arrayOf("SxS Dedicated Settings", "SxS專屬設定", "SxS专属设置", "SxS専用設定"),
        "Cal_Vehicle" to arrayOf("Vehicle", "車輛設定", "车辆设置", "車両設定"),
    )
}
