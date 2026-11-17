package xb.tune

class XmlException(message: String, val offset: Int) : Exception("$message (at $offset)")

class XmlAttribute(val name: String, val value: String, val valueStart: Int, val valueEnd: Int)

/**
 * 一個元素，連同它在原文裡的位置。
 * 位置是為了存檔：沒動過的部分要原文照寫，只替換改過的那幾段。
 */
class XmlElement internal constructor(
    val name: String,
    val attributes: List<XmlAttribute>,
    val children: List<XmlElement>,
    private val source: String,
    /** 整個元素，含起訖標籤。 */
    val start: Int,
    val end: Int,
    /** 起訖標籤之間；自閉合元素兩者相等。 */
    val innerStart: Int,
    val innerEnd: Int,
) {
    fun attr(name: String): String? = attributes.firstOrNull { it.name == name }?.value
    fun child(name: String): XmlElement? = children.firstOrNull { it.name == name }
    fun children(name: String): List<XmlElement> = children.filter { it.name == name }

    /** 沿路徑往下找第一個符合的元素，例如 `find("TableValue", "Table")`。 */
    fun find(vararg path: String): XmlElement? {
        var node: XmlElement? = this
        for (p in path) node = node?.child(p) ?: return null
        return node
    }

    /** 所有後代文字串接，與 .NET 的 InnerText 相同。 */
    val innerText: String
        get() {
            if (children.isEmpty()) return Xml.decodeText(source, innerStart, innerEnd)
            val sb = StringBuilder()
            var at = innerStart
            for (c in children) {
                sb.append(Xml.decodeText(source, at, c.start))
                sb.append(c.innerText)
                at = c.end
            }
            sb.append(Xml.decodeText(source, at, innerEnd))
            return sb.toString()
        }
}

/**
 * 夠用的 XML 讀取器：元素、屬性、文字、註解、CDATA、宣告。
 * 不處理命名空間與 DTD；定義檔與調校檔都用不到。
 */
object Xml {
    private const val MAX_DEPTH = 64
    private const val MAX_ELEMENTS = 300_000
    fun parse(text: String): XmlElement {
        class Open(val name: String, val attrs: List<XmlAttribute>, val start: Int, val innerStart: Int) {
            val children = ArrayList<XmlElement>()
        }

        val stack = ArrayList<Open>()
        var root: XmlElement? = null
        var elements = 0
        var i = 0
        val n = text.length
        while (true) {
            val lt = text.indexOf('<', i)
            if (lt < 0) break
            when {
                text.startsWith("<!--", lt) -> i = endOf(text, "-->", lt + 4)
                text.startsWith("<![CDATA[", lt) -> i = endOf(text, "]]>", lt + 9)
                text.startsWith("<?", lt) -> i = endOf(text, "?>", lt + 2)
                text.startsWith("<!", lt) -> i = endOf(text, ">", lt + 2)
                text.startsWith("</", lt) -> {
                    val gt = text.indexOf('>', lt)
                    if (gt < 0) throw XmlException("Unclosed end tag", lt)
                    val name = text.substring(lt + 2, gt).trim()
                    val open = stack.removeLastOrNull() ?: throw XmlException("Unexpected end tag </$name>", lt)
                    if (open.name != name) throw XmlException("Mismatched tags: <${open.name}> closed by </$name>", lt)
                    val el = XmlElement(open.name, open.attrs, open.children, text, open.start, gt + 1, open.innerStart, lt)
                    if (stack.isEmpty()) {
                        if (root != null) throw XmlException("Two root elements", lt)
                        root = el
                    } else stack.last().children.add(el)
                    i = gt + 1
                }
                else -> {
                    if (stack.size >= MAX_DEPTH) throw XmlException("XML nesting limit exceeded", lt)
                    if (++elements > MAX_ELEMENTS) throw XmlException("XML element limit exceeded", lt)
                    var p = lt + 1
                    while (p < n && !isSpace(text[p]) && text[p] != '>' && text[p] != '/') p++
                    val name = text.substring(lt + 1, p)
                    if (name.isEmpty()) throw XmlException("Empty tag name", lt)
                    val attrs = ArrayList<XmlAttribute>()
                    var selfClosing = false
                    while (true) {
                        while (p < n && isSpace(text[p])) p++
                        if (p >= n) throw XmlException("Tag <$name> is not closed", lt)
                        if (text[p] == '>') { p++; break }
                        if (text[p] == '/') {
                            if (p + 1 >= n || text[p + 1] != '>') throw XmlException("Malformed tag <$name>", p)
                            selfClosing = true; p += 2; break
                        }
                        val an = p
                        while (p < n && text[p] != '=' && !isSpace(text[p]) && text[p] != '>' && text[p] != '/') p++
                        val attrName = text.substring(an, p)
                        while (p < n && isSpace(text[p])) p++
                        if (p >= n || text[p] != '=') throw XmlException("Attribute $attrName has no value", an)
                        p++
                        while (p < n && isSpace(text[p])) p++
                        if (p >= n || (text[p] != '"' && text[p] != '\'')) throw XmlException("Attribute $attrName value is not quoted", p)
                        val quote = text[p]
                        val vs = p + 1
                        val ve = text.indexOf(quote, vs)
                        if (ve < 0) throw XmlException("Attribute $attrName quote is not closed", vs)
                        attrs.add(XmlAttribute(attrName, decodeEntities(text.substring(vs, ve)), vs, ve))
                        p = ve + 1
                    }
                    if (selfClosing) {
                        val el = XmlElement(name, attrs, emptyList(), text, lt, p, p, p)
                        if (stack.isEmpty()) {
                            if (root != null) throw XmlException("Two root elements", lt)
                            root = el
                        } else stack.last().children.add(el)
                    } else stack.add(Open(name, attrs, lt, p))
                    i = p
                }
            }
        }
        if (stack.isNotEmpty()) throw XmlException("Tag <${stack.last().name}> is never closed", stack.last().start)
        return root ?: throw XmlException("No root element", 0)
    }

    private fun endOf(text: String, marker: String, from: Int): Int {
        val at = text.indexOf(marker, from)
        if (at < 0) throw XmlException("$marker not found", from)
        return at + marker.length
    }

    private fun isSpace(c: Char) = c == ' ' || c == '\n' || c == '\r' || c == '\t'

    /** 一段不含子元素的內容轉成文字：去掉註解、展開 CDATA、解實體、換行統一成 \n。 */
    internal fun decodeText(source: String, from: Int, to: Int): String {
        if (from >= to) return ""
        val lt = source.indexOf('<', from)
        val plain = lt < 0 || lt >= to
        val raw = if (plain) source.substring(from, to) else {
            val sb = StringBuilder()
            var i = from
            while (i < to) {
                val m = source.indexOf('<', i)
                if (m < 0 || m >= to) { sb.append(decodeEntities(source.substring(i, to))); break }
                sb.append(decodeEntities(source.substring(i, m)))
                when {
                    source.startsWith("<![CDATA[", m) -> {
                        val e = source.indexOf("]]>", m + 9)
                        sb.append(source, m + 9, e)
                        i = e + 3
                    }
                    source.startsWith("<!--", m) -> i = source.indexOf("-->", m + 4) + 3
                    source.startsWith("<?", m) -> i = source.indexOf("?>", m + 2) + 2
                    else -> i = m + 1
                }
            }
            return normalizeNewlines(sb.toString())
        }
        return normalizeNewlines(decodeEntities(raw))
    }

    private fun normalizeNewlines(s: String): String =
        if (s.indexOf('\r') < 0) s else s.replace("\r\n", "\n").replace('\r', '\n')

    internal fun decodeEntities(s: String): String {
        if (s.indexOf('&') < 0) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '&') { sb.append(c); i++; continue }
            val semi = s.indexOf(';', i)
            if (semi < 0 || semi - i > 10) { sb.append(c); i++; continue }
            val ent = s.substring(i + 1, semi)
            val rep: String? = when {
                ent == "amp" -> "&"
                ent == "lt" -> "<"
                ent == "gt" -> ">"
                ent == "quot" -> "\""
                ent == "apos" -> "'"
                ent.startsWith("#x") || ent.startsWith("#X") -> ent.substring(2).toIntOrNull(16)?.let(::codePoint)
                ent.startsWith("#") -> ent.substring(1).toIntOrNull()?.let(::codePoint)
                else -> null
            }
            if (rep == null) { sb.append(c); i++ } else { sb.append(rep); i = semi + 1 }
        }
        return sb.toString()
    }

    private fun codePoint(cp: Int): String =
        if (cp < 0x10000) cp.toChar().toString()
        else {
            val v = cp - 0x10000
            charArrayOf((0xD800 + (v shr 10)).toChar(), (0xDC00 + (v and 0x3FF)).toChar()).concatToString()
        }
}
