package app.minerguru.model

import org.json.JSONObject

/** Reads GitHub's answer about the latest release and compares versions. */
object UpdateCheck {
    private val VERSION = Regex("""\d+(\.\d+){0,3}""")

    /** The version of a published, final release, without a leading `v`; `null` for anything else. */
    fun versionFromResponse(body: String): String? {
        val release = runCatching { JSONObject(body) }.getOrNull() ?: return null
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null
        val tag = release.optString("tag_name").removePrefix("v")
        return tag.takeIf { VERSION.matches(it) }
    }

    fun isNewer(candidate: String, current: String): Boolean {
        val new = candidate.split(".").map { it.toIntOrNull() ?: 0 }
        val old = current.split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(new.size, old.size)) {
            val a = new.getOrElse(i) { 0 }
            val b = old.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }
}
