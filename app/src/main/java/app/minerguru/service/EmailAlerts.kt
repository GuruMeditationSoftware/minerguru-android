package app.minerguru.service

import android.content.Context
import android.util.Log
import app.minerguru.model.AlertEvent
import app.minerguru.model.L
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

data class EmailSettings(
    val isEnabled: Boolean = false,
    val host: String = "",
    val port: Int = SmtpSecurity.START_TLS.defaultPort,
    val security: SmtpSecurity = SmtpSecurity.START_TLS,
    val username: String = "",
    val sender: String = "",
    /** Several addresses separated by commas. */
    val recipients: String = ""
) {
    val recipientList: List<String> get() = recipients.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }
    val isComplete: Boolean get() = host.isNotBlank() && port in 1..65535 && '@' in sender && recipientList.isNotEmpty()

    fun toJson(): JSONObject = JSONObject().put("enabled", isEnabled).put("host", host).put("port", port).put("security", security.name)
        .put("username", username).put("sender", sender).put("recipients", recipients)

    companion object {
        fun fromJson(o: JSONObject) = EmailSettings(o.optBoolean("enabled"), o.optString("host"), o.optInt("port", 587),
            runCatching { SmtpSecurity.valueOf(o.optString("security")) }.getOrDefault(SmtpSecurity.START_TLS),
            o.optString("username"), o.optString("sender"), o.optString("recipients"))
    }
}

/**
 * Sends alerts by mail on top of the notification. The password is encrypted with a key in the Android Keystore;
 * the key never leaves the device, so a copy of the preferences is of no use.
 */
object EmailAlerts {
    private const val PREFS = "email"
    private const val KEY_SETTINGS = "settings"
    private const val KEY_PASSWORD = "password"
    private val box = SecretBox("minerguru.smtp")
    private const val TAG = "EmailAlerts"

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun settings(context: Context): EmailSettings = prefs(context).getString(KEY_SETTINGS, null)
        ?.let { runCatching { EmailSettings.fromJson(JSONObject(it)) }.getOrNull() } ?: EmailSettings()

    fun setSettings(context: Context, settings: EmailSettings) = prefs(context).edit().putString(KEY_SETTINGS, settings.toJson().toString()).apply()

    fun hasPassword(context: Context) = prefs(context).contains(KEY_PASSWORD)

    fun setPassword(context: Context, password: String) {
        if (password.isEmpty()) {
            prefs(context).edit().remove(KEY_PASSWORD).apply()
            return
        }
        prefs(context).edit().putString(KEY_PASSWORD, box.seal(password)).apply()
    }

    private fun password(context: Context): String =
        prefs(context).getString(KEY_PASSWORD, null)?.let(box::open).orEmpty()

    private suspend fun send(context: Context, settings: EmailSettings, subject: String, body: String) = withContext(Dispatchers.IO) {
        val server = SmtpServer(settings.host.trim(), settings.port, settings.security, settings.username, password(context))
        SmtpClient.send(SmtpMessage(settings.sender.trim(), settings.recipientList, subject, body), server)
    }

    /** Throws what went wrong, for the test button. */
    suspend fun sendTest(context: Context, settings: EmailSettings) = send(context, settings, L("MinerGuru: Test-Mail", "MinerGuru: test mail"),
        L("Diese Test-Mail zeigt: Warnungen von MinerGuru kommen bei dir an.", "This test mail shows that alerts from MinerGuru reach you."))

    /** A failed mail is logged, the alert itself stays delivered. */
    suspend fun deliver(context: Context, events: List<AlertEvent>) {
        if (events.isEmpty()) return
        val settings = settings(context)
        if (!settings.isEnabled || !settings.isComplete) return
        for (event in AlertEvent.combine(events)) {
            try {
                send(context, settings, "MinerGuru: ${event.title}", event.body)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Warn-Mail nicht versendet: ${e.message}")
            }
        }
    }
}
