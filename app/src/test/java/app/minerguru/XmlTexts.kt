package app.minerguru

import app.minerguru.model.AppLanguage
import app.minerguru.model.Lang
import app.minerguru.model.TextSource
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/**
 * The texts straight from `res/values/strings.xml` and `res/values-de/strings.xml`, so unit tests run without
 * Android resources. Unescapes like aapt: `\n`, `\'`, `\"`, `\\`, `\uXXXX` and quoted sections.
 */
object XmlTexts : TextSource {
    private val stringNames by lazy { R.string::class.java.fields.associate { it.getInt(null) to it.name } }
    private val pluralNames by lazy { R.plurals::class.java.fields.associate { it.getInt(null) to it.name } }
    private val english by lazy { load("values") }
    private val german by lazy { load("values-de") }

    private class Texts(val strings: Map<String, String>, val plurals: Map<String, Map<String, String>>)

    private fun load(folder: String): Texts {
        val file = listOf(File("src/main/res/$folder/strings.xml"), File("app/src/main/res/$folder/strings.xml")).first { it.exists() }
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
        val strings = root.getElementsByTagName("string").let { list ->
            (0 until list.length).map { list.item(it) as Element }.associate { it.getAttribute("name") to unescape(it.textContent) }
        }
        val plurals = root.getElementsByTagName("plurals").let { list ->
            (0 until list.length).map { list.item(it) as Element }.associate { plural ->
                val items = plural.getElementsByTagName("item")
                plural.getAttribute("name") to (0 until items.length).map { items.item(it) as Element }
                    .associate { it.getAttribute("quantity") to unescape(it.textContent) }
            }
        }
        return Texts(strings, plurals)
    }

    private fun unescape(raw: String): String {
        val out = StringBuilder()
        var quoted = false
        var i = 0
        while (i < raw.length) {
            val c = raw[i]
            when {
                c == '\\' && i + 1 < raw.length -> {
                    when (val next = raw[i + 1]) {
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        'u' -> { out.append(raw.substring(i + 2, i + 6).toInt(16).toChar()); i += 4 }
                        else -> out.append(next)
                    }
                    i += 2
                }
                c == '"' -> { quoted = !quoted; i++ }
                !quoted && c.isWhitespace() -> {
                    if (out.isNotEmpty() && out.last() != ' ') out.append(' ')
                    i++
                }
                else -> { out.append(c); i++ }
            }
        }
        return if (raw.trimStart().startsWith('"')) out.toString() else out.toString().trim()
    }

    private fun texts(english: Boolean) = if (english) this.english else german

    override fun string(id: Int, english: Boolean): String {
        val name = stringNames.getValue(id)
        return texts(english).strings[name] ?: this.english.strings.getValue(name)
    }

    override fun plural(id: Int, count: Int, english: Boolean): String {
        val forms = texts(english).plurals.getValue(pluralNames.getValue(id))
        return forms[if (count == 1) "one" else "other"] ?: forms.getValue("other")
    }
}

/** German texts from the XML files, as most tests expect. */
fun useGermanTexts() {
    Lang.source = XmlTexts
    Lang.apply(AppLanguage.GERMAN)
}

/** English texts from the XML files. */
fun useEnglishTexts() {
    Lang.source = XmlTexts
    Lang.apply(AppLanguage.ENGLISH)
}
