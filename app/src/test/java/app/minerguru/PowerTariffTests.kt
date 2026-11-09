package app.minerguru

import app.minerguru.model.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class PowerTariffTests {
    @Before fun germanUi() = Lang.apply(AppLanguage.GERMAN)

    private val berlin = ZoneId.of("Europe/Berlin")

    /** 2026-11-02 is a Monday. */
    private fun at(day: Int, hour: Int) = ZonedDateTime.of(2026, 11, day, hour, 0, 0, 0, berlin).toInstant().toEpochMilli()

    private val night = TariffZone(name = "Nacht", pricePerKWh = 0.24, window = WeeklyWindow(Weekdays.all, 22 * 60, 6 * 60))
    private val day = TariffZone(name = "Tag", pricePerKWh = 0.36, window = WeeklyWindow(Weekdays.workdays, 6 * 60, 22 * 60))
    private val tariff = PowerTariff("EUR", 0.30, listOf(night, day))

    @Test fun windowOverMidnightBelongsToTheStartDay() {
        val friNight = WeeklyWindow(setOf(5), 22 * 60, 6 * 60)
        assertTrue(friNight.contains(5, 23 * 60))
        assertTrue(friNight.contains(6, 2 * 60))   // Saturday early morning
        assertFalse(friNight.contains(5, 2 * 60))  // Friday early morning belongs to Thursday
        assertFalse(friNight.contains(6, 23 * 60))
    }

    @Test fun sameStartAndEndCoversTheWholeDay() {
        val weekend = WeeklyWindow(Weekdays.weekend, 0, 0)
        assertTrue(weekend.contains(7, 0))
        assertTrue(weekend.contains(6, 23 * 60 + 59))
        assertFalse(weekend.contains(1, 12 * 60))
    }

    @Test fun priceFollowsZonesAndFallsBackToBasePrice() {
        assertEquals(0.36, tariff.priceAt(at(2, 12), berlin), 1e-9)  // Monday noon: day
        assertEquals(0.24, tariff.priceAt(at(3, 3), berlin), 1e-9)   // Tuesday 3 am: night
        assertEquals(0.30, tariff.priceAt(at(7, 12), berlin), 1e-9)  // Saturday noon: no zone
        assertTrue(tariff.hasGaps)
        assertTrue(tariff.overlappingZones.isEmpty())
    }

    @Test fun firstZoneWinsOnOverlap() {
        val peak = TariffZone(name = "Spitze", pricePerKWh = 0.50, window = WeeklyWindow(Weekdays.workdays, 17 * 60, 20 * 60))
        val withPeak = PowerTariff("EUR", 0.30, listOf(peak, night, day))
        assertEquals(0.50, withPeak.priceAt(at(2, 18), berlin), 1e-9)
        assertEquals(1, withPeak.overlappingZones.size)
    }

    @Test fun segmentsSplitTheDayAtZoneBorders() {
        val monday = tariff.segments(1)
        assertEquals(listOf(night.id, day.id, night.id), monday.map { it.zoneId })
        assertEquals(6 * 60, monday.first().endMinute)
        assertEquals(listOf(night.id, null, night.id), tariff.segments(6).map { it.zoneId })
    }

    @Test fun rulesFollowTheirZoneAndFirstRuleWins() {
        val rules = listOf(
            ScheduleRule(trigger = ScheduleTrigger.Zone(night.id), action = PowerAction.PAUSE, minerIds = setOf("a")),
            ScheduleRule(trigger = ScheduleTrigger.Zone(night.id), action = PowerAction.NIGHT_MODE)
        )
        assertEquals(PowerAction.PAUSE, PowerSchedule.action("a", at(3, 3), rules, tariff, berlin))
        assertEquals(PowerAction.NIGHT_MODE, PowerSchedule.action("b", at(3, 3), rules, tariff, berlin))
        assertEquals(PowerAction.NORMAL, PowerSchedule.action("a", at(3, 12), rules, tariff, berlin))

        // The zone moves, the rule moves with it.
        val later = tariff.copy(zones = listOf(night.copy(window = night.window.copy(startMinute = 60)), day.copy(window = day.window.copy(endMinute = 60))))
        assertEquals(PowerAction.NORMAL, PowerSchedule.action("b", at(2, 23), rules, later, berlin))
    }

    @Test fun costUsesThePriceOfEveryQuarterHourAndSkipsPauses() {
        // 100 W for a whole Monday: 6 h night + 16 h day + 2 h night.
        val monday = at(2, 0)
        val cost = PowerSchedule.cost(100.0, monday, 24.0, tariff, zone = berlin)
        assertEquals(0.8 * 0.24 + 1.6 * 0.36, cost, 1e-9)

        val pauseAtNight = listOf(ScheduleRule(trigger = ScheduleTrigger.Zone(night.id), action = PowerAction.PAUSE))
        assertEquals(1.6 * 0.36, PowerSchedule.cost(100.0, monday, 24.0, tariff, pauseAtNight, "m", zone = berlin), 1e-9)
        // Firmware without pause keeps drawing power.
        assertEquals(cost, PowerSchedule.cost(100.0, monday, 24.0, tariff, pauseAtNight, "m", canPause = false, zone = berlin), 1e-9)
    }

    @Test fun tariffAndRulesSurviveJson() {
        assertEquals(tariff, PowerTariff.fromJson(JSONObject(tariff.toJson().toString())))
        val rules = listOf(
            ScheduleRule(trigger = ScheduleTrigger.Zone(night.id), action = PowerAction.PAUSE, minerIds = setOf("a", "b")),
            ScheduleRule(trigger = ScheduleTrigger.Window(WeeklyWindow(Weekdays.weekend, 60, 120)), action = PowerAction.NIGHT_MODE, isEnabled = false)
        )
        rules.forEach { assertEquals(it, ScheduleRule.fromJson(JSONObject(it.toJson().toString()))) }
    }
}
