package app.minerguru

import app.minerguru.model.ConnectionState
import app.minerguru.model.MinerDevice
import app.minerguru.model.MinerSystemInfo
import app.minerguru.model.OtpSession
import app.minerguru.service.MinerApiException
import app.minerguru.service.OtpException
import app.minerguru.service.OtpSessions
import app.minerguru.service.OtpVault
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OtpSessionsTests {
    private val day = 86_400_000L
    private val refused = MinerApiException.ofResponse(401, "OTP/Session required")

    private fun miner(otp: Boolean = true) = MinerDevice(
        id = "m1",
        ipAddress = "192.0.2.1",
        connectionState = ConnectionState.Online,
        latestInfo = MinerSystemInfo.fromJson(JSONObject().put("otp", otp))
    )

    private class MemoryVault : OtpVault {
        val sessions = mutableMapOf<String, OtpSession>()
        override fun load(minerId: String) = sessions[minerId]
        override fun save(minerId: String, session: OtpSession?) {
            if (session == null) sessions.remove(minerId) else sessions[minerId] = session
        }
    }

    private class Script(vararg answers: String?) {
        val answers = answers.toMutableList()
        val problems = mutableListOf<String?>()
    }

    private var clock = 1_000_000L

    private fun sessions(
        script: Script,
        vault: OtpVault = MemoryVault(),
        create: suspend (String, String) -> OtpSession = { _, code -> OtpSession("t$code", clock + day) }
    ) = OtpSessions(vault, create) { clock }.also {
        it.prompt = { _, problem ->
            script.problems += problem
            if (script.answers.isEmpty()) null else script.answers.removeAt(0)
        }
    }

    @Test fun expiryComesFromTheDurationNotTheMinersClock() {
        val session = OtpSession.parseResponse("""{"token":"abc","ttlMs":86400000,"expiresAt":5}""", 1000)
        assertEquals("abc", session.token)
        assertEquals(1000 + day, session.expiresAt)
        assertTrue(session.isUsable(1000))
        assertFalse(session.isUsable(1000 + day - 30_000))
        assertEquals(session, OtpSession.fromJson(session.toJson()))
    }

    @Test fun recognisesTheAnswersOfTheFirmware() {
        assertTrue(refused.isOtpRefused)
        val blocked = MinerApiException.ofResponse(401, "blocked for 5 minutes")
        assertTrue(blocked.isOtpBlocked)
        assertFalse(blocked.isOtpRefused)
        assertFalse(MinerApiException.ofResponse(401, "Unauthorized").isOtpRefused)
        assertTrue(MinerApiException.ofResponse(401, "Invalid TOTP").isOtpWrongCode)
    }

    @Test fun minerWithoutOtpNeverAsks() = runBlocking {
        val script = Script()
        assertNull(sessions(script).perform(miner(otp = false), true) { it })
        assertTrue(script.problems.isEmpty())
    }

    @Test fun asksOnceAndReusesTheSession() = runBlocking {
        val script = Script("123456")
        val otp = sessions(script)
        assertEquals("t123456", otp.perform(miner(), true) { it })
        assertEquals("t123456", otp.perform(miner(), true) { it })
        assertEquals(1, script.problems.size)
        assertTrue(otp.canWriteUnattended(miner()))
    }

    @Test fun asksAgainAfterAMalformedOrWrongCode() = runBlocking {
        val script = Script("12", "111111", " 123456 ")
        val otp = sessions(script, create = { _, code ->
            if (code != "123456") throw MinerApiException.ofResponse(401, "Invalid TOTP")
            OtpSession("ok", clock + day)
        })
        assertEquals("ok", otp.authorize(miner()))
        assertEquals(3, script.problems.size)
        assertNull(script.problems[0])
        assertNotNull(script.problems[1])
        assertNotNull(script.problems[2])
    }

    @Test fun cancellingWritesNothing() {
        var writes = 0
        assertThrows(OtpException::class.java) {
            runBlocking { sessions(Script(null)).perform(miner(), true) { writes++ } }
        }
        assertEquals(0, writes)
    }

    @Test fun minerThatTurnedOtpOffGoesOn() = runBlocking {
        val otp = sessions(Script("123456"), create = { _, _ -> throw MinerApiException.ofResponse(405, "") })
        assertNull(otp.perform(miner(), true) { it })
    }

    @Test fun aRefusedSessionIsReplacedOnce() = runBlocking {
        val otp = sessions(Script("123456", "654321"))
        var writes = 0
        val sent = otp.perform(miner(), true) { token ->
            if (++writes == 1) throw refused
            token
        }
        assertEquals("t654321", sent)
        assertEquals(2, writes)
    }

    @Test fun unattendedWriteWithoutSessionSendsNothing() {
        val script = Script("123456")
        var writes = 0
        assertThrows(OtpException::class.java) {
            runBlocking { sessions(script).perform(miner(), false) { writes++ } }
        }
        assertEquals(0, writes)
        assertTrue(script.problems.isEmpty())
    }

    @Test fun unattendedWriteDropsARefusedSession() = runBlocking {
        val otp = sessions(Script("123456"))
        otp.authorize(miner())
        var thrown = false
        try {
            otp.perform<Unit>(miner(), false) { throw refused }
        } catch (_: MinerApiException) {
            thrown = true
        }
        assertTrue(thrown)
        assertFalse(otp.canWriteUnattended(miner()))
    }

    @Test fun expiredSessionDoesNotCountForSchedules() = runBlocking {
        val otp = sessions(Script("123456"))
        otp.authorize(miner())
        assertTrue(otp.canWriteUnattended(miner()))
        clock += day
        assertFalse(otp.canWriteUnattended(miner()))
    }
}
