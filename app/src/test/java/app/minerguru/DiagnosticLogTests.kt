package app.minerguru

import app.minerguru.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class DiagnosticLogTests {
    @get:Rule val temp = TemporaryFolder()

    private fun log() = DiagnosticLog(File(temp.root, DiagnosticLog.FILE_NAME))

    @Test fun writesOneLinePerEvent() {
        val log = log()
        log.write("state", "first\nsecond")
        log.write("alert", "third")
        val lines = log.exportText().trim().lines()
        assertEquals(2, lines.size)
        assertTrue(lines[0].contains("[state] first second"))
    }

    @Test fun keepsOnlyTwoFiles() {
        val log = log()
        val line = "x".repeat(100_000)
        repeat(25) { log.write("test", "$it $line") }
        assertEquals(listOf("minerguru.1.log", "minerguru.log"), temp.root.list()!!.sorted())
        val text = log.exportText()
        assertFalse(text.contains("] 0 x"))
        assertTrue(text.contains("] 24 x"))
        assertTrue(text.length <= 2 * DiagnosticLog.MAX_BYTES)
    }

    @Test fun staysSilentUntilActivated() {
        val log = DiagnosticLog()
        log.write("test", "nothing")
        assertEquals("", log.exportText())
    }

    @Test fun profileNamesFieldsButNoValues() {
        val raw = """{"stratumUser":"bc1qsecret.worker","stratumPassword":"hunter2","ssid":"HomeWifi","temp":55}"""
        val info = Fixtures.info(mapOf("ASICModel" to "BM1370"))
        val profile = DiagnosticLog.profile(MinerDevice(id = "m1", ipAddress = "192.168.1.50", rawJSON = raw), info)
        assertTrue(profile.contains("keys=ssid,stratumPassword,stratumUser,temp"))
        listOf("secret", "hunter2", "HomeWifi").forEach { assertFalse(profile.contains(it)) }
    }

    @Test fun measurementsEveryFifteenMinutesAcrossProcessDeath() {
        val start = 1_800_000_000_000L
        val info = Fixtures.info(mapOf("temp" to 55.0, "hashRate_1m" to 1000.0, "sharesAccepted" to 5, "uptimeSeconds" to 7200))
        var health = MinerHealth()
        health.update(info, start)
        assertTrue(health.snapshot(info, start)!!.contains("temp=55.0"))
        health = MinerHealth.fromJson(health.toJson())
        assertNull(health.snapshot(info, start + 14 * 60_000))
        assertNotNull(health.snapshot(info, start + 15 * 60_000))
    }
}
