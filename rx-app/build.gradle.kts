// rx-app：ReX 的 App 層（Repo 狀態＋全部畫面），Android 與 iOS 共用（Compose Multiplatform）。
// 平台差異在 Platform 介面：Android 實作在 app 模組，iOS 實作在 iosMain（rx-ios 打包成 iOS App）。
import javax.xml.parsers.DocumentBuilderFactory

plugins {
    kotlin("multiplatform")
    kotlin("plugin.compose")
    id("org.jetbrains.compose")
}

// Mac 上只建 iOS 時不需要 Android SDK：-Prx.iosOnly=true 就不套 Android 外掛
val iosOnly = providers.gradleProperty("rx.iosOnly").isPresent
if (!iosOnly) apply(plugin = "com.android.kotlin.multiplatform.library")

// 字串表：唯一正本是 app/src/main/res/values*/strings.xml（Android 清單／通知也用它）。
// 這裡在建置時轉成 Kotlin 表，iOS 與共用畫面用同一份；語言由 App 自己依系統偏好挑（繁／簡要看文字系統，不只看地區）。
val stringsSrc = rootDir.resolve("app/src/main/res")
val stringsOut = layout.buildDirectory.dir("generated/rxstrings/commonMain/kotlin")
val genStrings = tasks.register("genStrings") {
    inputs.dir(stringsSrc)
    outputs.dir(stringsOut)
    doLast {
        fun load(dir: String): Map<String, String> {
            val f = stringsSrc.resolve("$dir/strings.xml")
            if (!f.exists()) return emptyMap()
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
            val nodes = doc.getElementsByTagName("string")
            return (0 until nodes.length).associate { i ->
                val e = nodes.item(i) as org.w3c.dom.Element
                var t = e.textContent
                if (t.length >= 2 && t.startsWith("\"") && t.endsWith("\"")) t = t.substring(1, t.length - 1)
                t = t.replace("\\'", "'").replace("\\\"", "\"").replace("\\n", "\n").replace("\\t", "\t").replace("\\@", "@").replace("\\?", "?")
                e.getAttribute("name") to t
            }
        }
        val en = load("values")
        val langs = linkedMapOf("en" to en, "zhHant" to load("values-b+zh+Hant"), "zhHans" to load("values-b+zh+Hans"), "ja" to load("values-ja"))
        val keys = en.keys.sorted()
        fun lit(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\t", "\\t").replace("$", "\\$") + "\""
        val sb = StringBuilder()
        sb.append("// 自動產生（rx-app genStrings），不要手改。來源：app/src/main/res/values*/strings.xml\n")
        sb.append("package io.github.xeno_hf.rex.res\n\n")
        sb.append("object R {\n    object string {\n")
        keys.forEachIndexed { i, k -> sb.append("        const val $k = $i\n") }
        sb.append("    }\n}\n\n")
        sb.append("internal object StringTables {\n")
        langs.forEach { (lang, m) ->
            sb.append("    val $lang: Array<String?> = arrayOf(\n")
            keys.forEach { k -> sb.append("        ").append(m[k]?.let(::lit) ?: "null").append(",\n") }
            sb.append("    )\n")
        }
        sb.append("}\n")
        val out = stringsOut.get().asFile.resolve("io/github/xeno_hf/rex/res/R.kt")
        out.parentFile.mkdirs()
        out.writeText(sb.toString())
    }
}

kotlin {
    // AGP 9 起 KMP 模組改用 com.android.kotlin.multiplatform.library，Android 目標設定寫在 kotlin { android {} } 裡
    if (!iosOnly) extensions.configure<com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget>("android") {
        namespace = "io.github.xeno_hf.rex.shared"
        compileSdk { version = release(37) { minorApiLevel = 2 } }
        minSdk = 29
        androidResources.enable = true
        compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
    // iOS：整個共用層輸出成 RexKit.framework，給 rx-ios 的 Swift 進入點用
    listOf(iosArm64(), iosSimulatorArm64()).forEach {
        it.binaries.framework { baseName = "RexKit"; isStatic = false }
    }

    sourceSets {
        commonMain {
            kotlin.srcDir(genStrings)
            kotlin.srcDir(rootDir.resolve("xb-calui/src/commonMain/kotlin"))
            dependencies {
                api(project(":rx-session"))
                api(project(":xb-link"))
                api("org.jetbrains.compose.runtime:runtime:1.12.2")
                api("org.jetbrains.compose.foundation:foundation:1.12.2")
                api("org.jetbrains.compose.material3:material3:1.9.0")
                api("org.jetbrains.compose.ui:ui:1.12.2")
                implementation("org.jetbrains.compose.components:components-resources:1.12.2")
                implementation("org.jetbrains.compose.material:material-icons-core:1.7.3")
            }
        }
        if (!iosOnly) androidMain.dependencies {
            implementation("androidx.activity:activity-compose:1.13.0")
            implementation("androidx.core:core-ktx:1.19.1")
        }
    }
}

compose.resources {
    publicResClass = false
    packageOfResClass = "io.github.xeno_hf.rex.res"
    generateResClass = always
}
