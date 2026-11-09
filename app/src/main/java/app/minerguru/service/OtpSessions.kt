package app.minerguru.service

import android.content.Context
import app.minerguru.R
import app.minerguru.model.tr
import app.minerguru.model.MinerDevice
import app.minerguru.model.OtpSession
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Thrown instead of writing: the user gave no code, or a schedule has no session. */
class OtpException(message: String) : Exception(message) {
    companion object {
        fun cancelled() = OtpException(tr(R.string.no_one_time_code_entered))
        fun codeNeeded() = OtpException(tr(R.string.this_miner_needs_a_one))
    }
}

/** Where sessions rest between app starts. */
interface OtpVault {
    fun load(minerId: String): OtpSession?
    fun save(minerId: String, session: OtpSession?)
}

/**
 * Sessions for miners that protect their settings with a one-time code. The code itself is never kept.
 * Schedules only use a stored session: five refused requests lock the miner for five minutes.
 */
class OtpSessions(
    private val vault: OtpVault,
    private val create: suspend (host: String, code: String) -> OtpSession,
    private val clock: () -> Long = System::currentTimeMillis
) {
    /** Asks for the code; `null` when the user cancels. Set while the app is on screen. */
    @Volatile var prompt: (suspend (minerName: String, problem: String?) -> String?)? = null

    private val asking = Mutex()

    /** The stored session of a miner, if it can still be used. */
    fun token(minerId: String, now: Long = clock()): String? = vault.load(minerId)?.takeIf { it.isUsable(now) }?.token

    /** Whether a schedule may write to this miner without anyone at the screen. */
    fun canWriteUnattended(miner: MinerDevice, now: Long = clock()): Boolean =
        !miner.requiresOtp || token(miner.id, now) != null

    fun invalidate(minerId: String) = vault.save(minerId, null)

    /** Returns a valid session, asking for a code if there is none; `null` when the miner no longer uses OTP. */
    suspend fun authorize(miner: MinerDevice): String? {
        token(miner.id)?.let { return it }
        return asking.withLock { token(miner.id) ?: askForCode(miner) }
    }

    private suspend fun askForCode(miner: MinerDevice): String? {
        val ask = prompt ?: throw OtpException.cancelled()
        var problem: String? = null
        while (true) {
            val code = (ask(miner.displayName, problem) ?: throw OtpException.cancelled()).trim()
            if (code.length != 6 || !code.all { it in '0'..'9' }) {
                problem = tr(R.string.the_code_has_6_digits)
                continue
            }
            try {
                val session = create(miner.ipAddress, code)
                vault.save(miner.id, session)
                return session.token
            } catch (e: MinerApiException) {
                if (e.status == 405) return null
                if (!e.isOtpWrongCode) throw e
                problem = tr(R.string.that_code_is_wrong_wait)
            }
        }
    }

    /**
     * Runs a write. [interactive] may ask for a code; otherwise only a stored session is used
     * and a refused one is dropped, so the next attempt does not cost another failed try.
     */
    suspend fun <T> perform(miner: MinerDevice, interactive: Boolean, write: suspend (String?) -> T): T {
        var token: String? = null
        if (miner.requiresOtp) {
            token = if (interactive) authorize(miner) else token(miner.id)
            if (token == null && !interactive) throw OtpException.codeNeeded()
        }
        return try {
            write(token)
        } catch (e: MinerApiException) {
            if (!e.isOtpRefused) throw e
            invalidate(miner.id)
            if (!interactive) throw e
            write(authorize(miner))
        }
    }
}

/** The app's one set of sessions: the view model and the foreground service share it. */
object OtpCenter {
    private const val PREFS = "otp"
    @Volatile private var instance: OtpSessions? = null

    fun get(context: Context): OtpSessions = instance ?: synchronized(this) {
        instance ?: OtpSessions(KeystoreVault(context.applicationContext), MinerApiClient::createOtpSession).also { instance = it }
    }

    private class KeystoreVault(context: Context) : OtpVault {
        private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        private val box = SecretBox("minerguru.otp")

        override fun load(minerId: String): OtpSession? =
            prefs.getString(minerId, null)?.let(box::open)?.let(OtpSession::fromJson)

        override fun save(minerId: String, session: OtpSession?) {
            prefs.edit().apply {
                if (session == null) remove(minerId) else putString(minerId, box.seal(session.toJson()))
            }.apply()
        }
    }
}
