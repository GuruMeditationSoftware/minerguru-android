package app.minerguru.model

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/** UI language setting. `SYSTEM` follows the device: German on a German system, English otherwise. */
enum class AppLanguage { SYSTEM, GERMAN, ENGLISH }

/**
 * Current UI language. Compose reads it as state, so switching the language updates the whole UI at once.
 * German is the source language; [L] picks the English text when English is active.
 */
object Lang {
    var english by mutableStateOf(false)
        private set

    fun apply(setting: AppLanguage, systemLanguage: String = Locale.getDefault().language) {
        english = when (setting) {
            AppLanguage.GERMAN -> false
            AppLanguage.ENGLISH -> true
            AppLanguage.SYSTEM -> systemLanguage.lowercase() != "de"
        }
    }
}

/** German text, or its English translation when the UI is in English. */
fun L(de: String, en: String): String = if (Lang.english) en else de

/** Count with the matching singular or plural noun, e.g. `Lp(n, "Pool", "Pools", "pool", "pools")` → "1 pool" / "3 pools". */
fun Lp(count: Int, deOne: String, deOther: String, enOne: String, enOther: String): String =
    "$count " + if (count == 1) L(deOne, enOne) else L(deOther, enOther)
