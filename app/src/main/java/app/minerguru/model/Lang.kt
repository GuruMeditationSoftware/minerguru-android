package app.minerguru.model

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/** UI language setting. `SYSTEM` follows the device: German on a German system, English otherwise. */
enum class AppLanguage { SYSTEM, GERMAN, ENGLISH }

/** Where the texts come from: the app's string resources, or in unit tests the XML files themselves. */
interface TextSource {
    fun string(@StringRes id: Int, english: Boolean): String
    fun plural(@PluralsRes id: Int, count: Int, english: Boolean): String
}

/**
 * Current UI language. Compose reads it as state, so switching the language updates the whole UI at once.
 * The texts live in `res/values/strings.xml` (English) and `res/values-de/strings.xml` (German); [tr] reads
 * them in this language, which can differ from the device's.
 */
object Lang {
    var english by mutableStateOf(false)
        private set

    @Volatile
    var source: TextSource? = null

    val locale: Locale get() = if (english) Locale.ENGLISH else Locale.GERMAN

    /** Connects the string resources; called by the application before anything shows text. */
    fun init(context: Context) {
        if (source == null) source = ResourceTextSource(context.applicationContext)
    }

    fun apply(setting: AppLanguage, systemLanguage: String = Locale.getDefault().language) {
        english = when (setting) {
            AppLanguage.GERMAN -> false
            AppLanguage.ENGLISH -> true
            AppLanguage.SYSTEM -> systemLanguage.lowercase() != "de"
        }
    }

    internal fun texts(): TextSource = source ?: error("Lang.init(context) has not run")
}

/** The app's resources in German and English, independent of the device language. */
private class ResourceTextSource(private val context: Context) : TextSource {
    private val german by lazy { resources(Locale.GERMAN) }
    private val english by lazy { resources(Locale.ENGLISH) }

    private fun resources(locale: Locale): Resources =
        context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(locale) }).resources

    private fun of(english: Boolean) = if (english) this.english else german

    override fun string(id: Int, english: Boolean): String = of(english).getString(id)
    override fun plural(id: Int, count: Int, english: Boolean): String = of(english).getQuantityString(id, count)
}

/** The text with this key in the UI language, e.g. `tr(R.string.fleet)`. */
fun tr(@StringRes id: Int): String = Lang.texts().string(id, Lang.english)

/** A text with arguments for its `%1$s`, `%2$s` …, e.g. `tr(R.string.miner_added, name)`. */
fun tr(@StringRes id: Int, vararg args: Any?): String = String.format(Lang.locale, tr(id), *args)

/** Count with the matching singular or plural form, e.g. `trp(R.plurals.pools, n)` → "1 pool" / "3 pools". */
fun trp(@PluralsRes id: Int, count: Int): String =
    String.format(Lang.locale, Lang.texts().plural(id, count, Lang.english), count)
