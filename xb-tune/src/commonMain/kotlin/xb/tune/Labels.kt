package xb.tune

/**
 * 表與群組給人看的名稱和說明。
 * 每一項有多種語言；找不到指定語言時退回英文，再退回內部名稱。
 */
class Labels(
    private val groups: Map<String, Map<String, String>>,
    private val shortNames: Map<Int, Map<String, String>>,
    private val descriptions: Map<Int, Map<String, String>>,
) {
    fun group(name: String, lang: String): String = pick(groups[name], lang) ?: name
    /** 短名；沒有就是 null，畫面改顯示內部名稱。 */
    fun shortName(absId: Int, lang: String): String? = pick(shortNames[absId], lang)
    fun description(absId: Int, lang: String): String? = pick(descriptions[absId], lang)

    val shortNameCount: Int get() = shortNames.size
    val descriptionCount: Int get() = descriptions.size

    private fun pick(m: Map<String, String>?, lang: String): String? =
        (m?.get(lang) ?: m?.get("en-US"))?.takeIf { it.isNotBlank() }

    operator fun plus(other: Labels): Labels =
        Labels(groups + other.groups, shortNames + other.shortNames, descriptions + other.descriptions)

    companion object {
        val EMPTY = Labels(emptyMap(), emptyMap(), emptyMap())

        /** 三份檔案都可省略。[idOffset]：附掛定義檔的表號位移。 */
        fun parse(groupDes: String?, tabShortName: String?, paraAndVarDes: String?, idOffset: Int = 0): Labels {
            fun texts(e: XmlElement) = e.children.associate { it.name to it.innerText.trim() }
            val groups = HashMap<String, Map<String, String>>()
            if (groupDes != null) for (g in Xml.parse(groupDes).children("Group")) {
                groups[g.attr("name") ?: continue] = texts(g)
            }
            fun byId(nodes: List<XmlElement>): Map<Int, Map<String, String>> {
                val out = HashMap<Int, Map<String, String>>()
                for (t in nodes) {
                    val id = t.attr("absid")?.trim()?.toIntOrNull() ?: continue
                    // 負的表號是 STX 介面用的虛擬項目，不加位移
                    out[if (id >= 0) id + idOffset else id] = texts(t)
                }
                return out
            }
            val short = if (tabShortName != null) byId(Xml.parse(tabShortName).children("Tab")) else emptyMap()
            val desc = if (paraAndVarDes != null) byId(Xml.parse(paraAndVarDes).child("Table")?.children("Tab") ?: emptyList()) else emptyMap()
            return Labels(groups, short, desc)
        }
    }
}
