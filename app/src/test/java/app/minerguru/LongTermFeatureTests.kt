package app.minerguru

import app.minerguru.model.*
import app.minerguru.service.SmtpDialogue
import app.minerguru.service.SmtpException
import app.minerguru.service.SmtpMessage
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import java.util.Base64

class HistoryAggregatorTests {
    private val start = 1_800_000_000_000L / 60_000 * 60_000
    private fun reading(time: Long, online: Boolean = true, hash: Double = 500.0, accepted: Int = 0) =
        HistoryReading("m", time, online, hash, 55.0, powerWatts = 15.0, fanPercent = 40.0, errorPercent = 0.1, sharesAccepted = accepted, bestDiff = 1000.0)

    @Test fun `averages polls and closes the minute`() {
        val a = HistoryAggregator()
        assertNull(a.add(reading(start, hash = 400.0)))
        assertNull(a.add(reading(start + 10_000, hash = 600.0)))
        val row = a.add(reading(start + 60_000))!!
        assertEquals(500.0, row.hashRateGHs!!, 0.001)
        assertEquals(1, row.onlineMinutes)
    }

    @Test fun `offline minutes count without values`() {
        val a = HistoryAggregator()
        a.add(reading(start, online = false))
        val row = a.flush(start).single()
        assertEquals(0, row.onlineMinutes)
        assertEquals(1, row.recordedMinutes)
        assertNull(row.hashRateGHs)
    }

    @Test fun `a sparse background poll stands for the gap, 15 minutes at most`() {
        val a = HistoryAggregator()
        a.add(reading(start)); a.flush(start + 60_000)
        a.add(reading(start + 10 * 60_000))
        assertEquals(10, a.flush(start + 11 * 60_000).single().recordedMinutes)
        a.add(reading(start + 120 * 60_000))
        assertEquals(15, a.flush(start + 121 * 60_000).single().recordedMinutes)
    }

    @Test fun `share counters survive a restart of the miner and of the worker`() {
        var a = HistoryAggregator()
        a.add(reading(start, accepted = 100))
        a.flush(start + 60_000)
        a = HistoryAggregator.fromJson(a.toJson().toString())
        a.add(reading(start + 60_000, accepted = 130))
        assertEquals(30, a.flush(start + 120_000).single().sharesAccepted)
        a.add(reading(start + 120_000, accepted = 5))
        assertEquals(5, a.flush(start + 180_000).single().sharesAccepted)
    }

    @Test fun `step keeps charts small`() {
        assertEquals(120, HistoryQuery.step(86_400))
        assertEquals(3600, HistoryQuery.step(7 * 86_400))
        assertTrue(HistoryQuery.step(365 * 86_400) >= 12 * 3600)
    }

    @Test fun `csv quotes names and uses dots`() {
        val csv = HistoryCsv.text(listOf(HistoryPoint("m", 0, 1, 1, hashRateGHs = 1000.0, powerWatts = 15.0))) { "a\"b" }
        assertTrue("\"a\"\"b\"" in csv)
        assertTrue("15.000" in csv)
        assertEquals(2, csv.trim().lines().size)
    }

    @Test fun `error percentage is read from the miner`() {
        assertEquals(0.5, Fixtures.bitaxeInfo(mapOf("errorPercentage" to 0.5)).errorPercentage!!, 0.001)
    }
}

class PoolScheduleTests {
    private val zone = ZoneId.of("Europe/Berlin")
    private fun time(d: Int, h: Int, m: Int = 0) = java.time.ZonedDateTime.of(2026, 11, d, h, m, 0, 0, zone).toInstant().toEpochMilli()
    // 2026-11-06 is a Friday, day 5 in the app's week.
    private val rule = PoolScheduleRule(poolId = "p", weekdays = setOf(5), minute = 22 * 60, createdAt = 0)

    @Test fun `finds the last occurrence`() {
        assertEquals(time(6, 22), rule.lastOccurrence(time(6, 23), zone))
        assertEquals(time(6, 22) - 7 * 86_400_000L, rule.lastOccurrence(time(6, 21), zone))
    }

    @Test fun `runs once and catches up for half an hour`() {
        assertEquals(time(6, 22), rule.dueOccurrence(time(6, 22, 1), null, zone))
        assertNull(rule.dueOccurrence(time(6, 22, 1), time(6, 22), zone))
        assertEquals(time(6, 22), rule.dueOccurrence(time(6, 22, 29), null, zone))
        assertNull(rule.dueOccurrence(time(6, 22, 31), null, zone))
    }

    @Test fun `a new rule does not fire for the past`() {
        assertNull(rule.copy(createdAt = time(6, 22, 5)).dueOccurrence(time(6, 22, 10), null, zone))
    }

    @Test fun `rule survives json`() {
        val r = rule.copy(minerIds = setOf("a"))
        assertEquals(r, PoolScheduleRule.fromJson(org.json.JSONObject(r.toJson().toString())))
    }

    @Test fun `primary pool moves to a library pool`() {
        val miner = MinerDevice(id = "m", ipAddress = "1.2.3.4", latestInfo = Fixtures.bitaxeInfo())
        val lotto = PoolConfig.create("lotto", "lotto.example.com", 3333, "bc1qtest")
        val form = MinerSettingsForm.from(miner).makePrimary(lotto)
        assertTrue(lotto.matches(form.primary))
    }
}

class MaintenanceTests {
    private val zone = ZoneId.of("Europe/Berlin")
    private fun time(d: Int, h: Int = 0) = java.time.ZonedDateTime.of(2026, 11, d, h, 0, 0, 0, zone).toInstant().toEpochMilli()

    @Test fun status() {
        val task = MaintenanceTask(kind = MaintenanceKind.FAN_CLEANING, intervalDays = 90, nextDue = time(3))
        val now = time(4, 12)
        assertEquals(MaintenanceStatus.OVERDUE, task.status(now, zone))
        assertEquals(MaintenanceStatus.DUE_SOON, task.copy(nextDue = time(10)).status(now, zone))
        assertEquals(MaintenanceStatus.PLANNED, task.copy(nextDue = time(30)).status(now, zone))
    }

    @Test fun `next date only for repeating tasks`() {
        val task = MaintenanceTask(kind = MaintenanceKind.DUST, intervalDays = 30, nextDue = time(1))
        assertEquals(time(4) + 30 * 86_400_000L + 0, task.nextDueAfterDone(time(4), ZoneId.of("UTC")))
        assertNull(task.copy(intervalDays = 0).nextDueAfterDone(time(4), zone))
    }

    @Test fun `task and record survive json`() {
        val task = MaintenanceTask(kind = MaintenanceKind.CUSTOM, customTitle = "Regal", minerIds = setOf("a"), intervalDays = 0, nextDue = 5, notes = "n")
        assertEquals(task, MaintenanceTask.fromJson(org.json.JSONObject(task.toJson().toString())))
        val record = MaintenanceRecord(kind = MaintenanceKind.DUST, title = "t", minerIds = setOf("a"), doneAt = 9)
        assertEquals(record, MaintenanceRecord.fromJson(org.json.JSONObject(record.toJson().toString())))
    }
}

class MinerReportTests {
    private val zone = ZoneId.of("Europe/Berlin")

    @Test fun `builds figures from hourly rows`() {
        val start = java.time.ZonedDateTime.of(2026, 11, 3, 0, 0, 0, 0, zone).toInstant().toEpochMilli()
        val hour = 3_600_000L
        val points = listOf(
            HistoryPoint("m", start, 60, 60, 1000.0, 50.0, 60.0, powerWatts = 20.0, sharesAccepted = 10, sharesRejected = 1, bestDiff = 100.0),
            HistoryPoint("m", start + hour, 30, 60, 400.0, 56.0, 70.0, powerWatts = 10.0, sharesAccepted = 5, bestDiff = 300.0)
        )
        val inside = MaintenanceRecord(kind = MaintenanceKind.FIRMWARE, title = "Update", minerIds = emptySet(), doneAt = start + 60_000)
        val outside = inside.copy(title = "Alt", doneAt = start - 60_000)
        val report = MinerReport.build(start, start + 2 * hour, points, { "Test" }, { 0.5 }, listOf(inside, outside), zone)
        val row = report.rows.single()
        assertEquals(75.0, row.uptimePercent!!, 0.001)
        assertEquals(800.0, row.averageHashRateGHs!!, 0.001)
        assertEquals(0.025, row.energyKWh, 1e-9)
        assertEquals(0.0125, row.cost, 1e-9)
        assertEquals(70.0, row.maxTempC!!, 0.001)
        assertEquals(15, row.sharesAccepted)
        assertEquals(300.0, report.bestDiff!!, 0.001)
        assertEquals(listOf("Update"), report.maintenance.map { it.title })
        assertTrue("\"Test\",75.00,800.00,0.025" in report.csvText())
    }

    @Test fun periods() {
        val now = java.time.ZonedDateTime.of(2026, 11, 4, 12, 0, 0, 0, zone)
        val (start, end) = ReportPeriod.LAST_MONTH.interval(now.toInstant().toEpochMilli(), zone)
        assertEquals(now.withMonth(10).withDayOfMonth(1).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli(), start)
        assertEquals(now.withDayOfMonth(1).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli(), end)
    }
}

class SmtpTests {
    private val b64 = { b: ByteArray -> Base64.getEncoder().encodeToString(b) }
    private val message = SmtpMessage("a@example.com", listOf("b@example.com", "c@example.com"), "Prüfung", "Hallo")
    private fun send(line: String) = SmtpDialogue.Action.Send(line)

    @Test fun `full dialogue with STARTTLS and login`() {
        val d = SmtpDialogue(message, "u", "p", true, b64, "x")
        assertEquals(send("EHLO x"), d.next(220, "220 hi"))
        assertEquals(send("STARTTLS"), d.next(250, "250-x\n250 STARTTLS"))
        assertEquals(SmtpDialogue.Action.StartTls, d.next(220, "220 go"))
        assertEquals(send("AUTH LOGIN"), d.next(250, "250 AUTH LOGIN"))
        assertEquals(send("dQ=="), d.next(334, "334 a"))
        assertEquals(send("cA=="), d.next(334, "334 b"))
        assertEquals(send("MAIL FROM:<a@example.com>"), d.next(235, "235 ok"))
        assertEquals(send("RCPT TO:<b@example.com>"), d.next(250, "250 ok"))
        assertEquals(send("RCPT TO:<c@example.com>"), d.next(250, "250 ok"))
        assertEquals(send("DATA"), d.next(250, "250 ok"))
        val data = (d.next(354, "354 go") as SmtpDialogue.Action.Send).line
        assertTrue(data.endsWith("\r\n."))
        assertTrue("Subject: =?UTF-8?B?" in data)
        assertEquals(send("QUIT"), d.next(250, "250 queued"))
        assertEquals(SmtpDialogue.Action.Done, d.next(221, "221 bye"))
    }

    @Test fun `no login without a user`() {
        val d = SmtpDialogue(message, "", "", false, b64, "x")
        d.next(220, "220 hi")
        assertEquals(send("MAIL FROM:<a@example.com>"), d.next(250, "250 ok"))
    }

    @Test(expected = SmtpException::class) fun `refuses to go on without STARTTLS`() {
        val d = SmtpDialogue(message, "u", "p", true, b64, "x")
        d.next(220, "220 hi")
        d.next(250, "250 PIPELINING")
    }

    @Test(expected = SmtpException::class) fun `wrong password is an error`() {
        val d = SmtpDialogue(message, "u", "p", false, b64, "x")
        d.next(220, "220 hi"); d.next(250, "250 ok"); d.next(334, "a"); d.next(334, "b")
        d.next(535, "535 nope")
    }

    @Test fun `dots are stuffed`() {
        assertEquals("a\r\n..b\r\n...", SmtpDialogue.dotStuffed("a\r\n.b\r\n.."))
    }
}

class DonationTests {
    @Test fun `placeholders do not count as addresses`() {
        assertTrue(DonationCoin.entries.none { it.isConfigured && "ADRESSE" in it.address })
    }

    @Test fun `qr writer handles a payment uri`() {
        val matrix = com.google.zxing.qrcode.QRCodeWriter().encode("bitcoin:bc1qtest", com.google.zxing.BarcodeFormat.QR_CODE, 0, 0)
        assertTrue(matrix.width > 20)
    }
}
