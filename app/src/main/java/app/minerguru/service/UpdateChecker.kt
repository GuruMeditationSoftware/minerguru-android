package app.minerguru.service

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.minerguru.BuildConfig
import app.minerguru.model.UpdateCheck
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.util.concurrent.TimeUnit

enum class UpdateStatus { IDLE, CHECKING, UP_TO_DATE, FAILED }

/** Asks GitHub once a day whether a newer version exists. It only tells the user: nothing is downloaded or installed. */
class UpdateChecker(
    private val prefs: SharedPreferences,
    private val scope: CoroutineScope,
    private val currentVersion: String = BuildConfig.VERSION_NAME,
    private val fetch: suspend () -> String? = { fetchLatestRelease() }
) {
    var isEnabled by mutableStateOf(prefs.getBoolean(KEY_ENABLED, true))
        private set
    var status by mutableStateOf(UpdateStatus.IDLE)
        private set
    var latestVersion by mutableStateOf(prefs.getString(KEY_LATEST, null))
        private set
    private var dismissedVersion by mutableStateOf(prefs.getString(KEY_DISMISSED, null))

    /** A version newer than this app, if the last answer named one. */
    val availableVersion: String?
        get() = latestVersion?.takeIf { UpdateCheck.isNewer(it, currentVersion) }

    /** The version the overview announces. */
    val bannerVersion: String?
        get() = availableVersion?.takeIf { isEnabled && it != dismissedVersion }

    fun start() {
        scope.launch {
            while (isActive) {
                checkIfDue()
                delay(WAKE_INTERVAL_MS)
            }
        }
    }

    fun updateEnabled(enabled: Boolean) {
        isEnabled = enabled
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun checkNow() {
        scope.launch { check() }
    }

    fun dismissBanner() {
        dismissedVersion = availableVersion
        prefs.edit().putString(KEY_DISMISSED, dismissedVersion).apply()
    }

    suspend fun checkIfDue(now: Long = System.currentTimeMillis()) {
        if (!isEnabled) return
        val last = prefs.getLong(KEY_LAST_CHECK, 0)
        if (last in 1..now && now - last < CHECK_INTERVAL_MS) return
        check(now)
    }

    suspend fun check(now: Long = System.currentTimeMillis()) {
        if (status == UpdateStatus.CHECKING) return
        status = UpdateStatus.CHECKING
        val version = runCatching { fetch() }.getOrNull()?.let(UpdateCheck::versionFromResponse)
        if (version == null) {
            status = UpdateStatus.FAILED
            return
        }
        latestVersion = version
        prefs.edit().putString(KEY_LATEST, version).putLong(KEY_LAST_CHECK, now).apply()
        status = UpdateStatus.UP_TO_DATE
    }

    companion object {
        const val CHECK_INTERVAL_MS = 24 * 60 * 60 * 1000L
        /** How often `start()` looks whether a check is due. A failed check is repeated at this pace. */
        private const val WAKE_INTERVAL_MS = 60 * 60 * 1000L
        private const val KEY_ENABLED = "updateCheckEnabled.v1"
        private const val KEY_LAST_CHECK = "updateLastCheck.v1"
        private const val KEY_LATEST = "updateLatestVersion.v1"
        private const val KEY_DISMISSED = "updateDismissedVersion.v1"

        /** The body of a successful answer, `null` otherwise. */
        private suspend fun fetchLatestRelease(): String? = withContext(Dispatchers.IO) {
            val request = Request.Builder().url(BuildConfig.UPDATE_URL)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "MinerGuru/${BuildConfig.VERSION_NAME}")
                .build()
            MinerApiClient.http.newBuilder()
                .connectTimeout(10, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()
                .newCall(request).execute().use { if (it.isSuccessful) it.body?.string() else null }
        }
    }
}
