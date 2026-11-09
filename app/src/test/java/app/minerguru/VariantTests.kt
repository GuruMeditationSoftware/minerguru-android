package app.minerguru

import app.minerguru.model.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

/** Every Bitaxe and NerdOS variant from the macOS app's `MockMiners/variants` (copied to test resources). */
class VariantTests {
    private data class Variant(
        val file: String, val title: String, val firmware: MinerFirmware, val chips: Int,
        val slots: Boolean, val dualPool: Boolean, val standard: Pair<Int, Int>, val presets: List<String>
    )

    private val ax = MinerFirmware.AXE_OS
    private val nerd = MinerFirmware.NERD_OS
    private val esb = listOf("Eco", "Standard", "Boost")
    private val es = listOf("Eco", "Standard")

    private val all = listOf(
        Variant("bitaxe-max-102_axeos-2.5.0", "Bitaxe Max 102", ax, 1, false, false, 425 to 1400, esb),
        Variant("bitaxe-ultra-204_axeos-2.8.0", "Bitaxe Ultra 204", ax, 1, false, false, 485 to 1200, esb),
        Variant("bitaxe-supra-401_axeos-2.10.0", "Bitaxe Supra 401", ax, 1, false, false, 490 to 1166, esb),
        Variant("bitaxe-hex-302_axeos-2.11.0", "Bitaxe Hex 302", ax, 6, false, false, 485 to 1200, esb),
        Variant("bitaxe-gamma-601_axeos-2.13.2", "Bitaxe Gamma 601", ax, 1, false, false, 525 to 1150, esb),
        Variant("bitaxe-supra-hex-701_axeos-2.14.2", "Bitaxe Supra Hex 701", ax, 6, false, false, 490 to 1166, esb),
        Variant("bitaxe-gamma-601_axeos-2.15.3", "Bitaxe Gamma 601", ax, 1, true, false, 525 to 1150, esb),
        Variant("bitaxe-gamma-duo-650_axeos-2.15.4", "Bitaxe Gamma Duo 650", ax, 2, true, false, 400 to 1150, es),
        Variant("bitaxe-gt-801_axeos-2.15.4", "Bitaxe GT 801", ax, 2, true, false, 525 to 1150, esb),
        Variant("bitaxe-naja-duo-1201_axeos-2.15.4", "Bitaxe Naja Duo 1201", ax, 2, true, false, 327 to 1000, listOf("Standard", "Boost")),
        Variant("bitaxe-gamma-hex-1300_axeos-2.15.4", "Bitaxe Gamma Hex 1300", ax, 6, true, false, 690 to 1200, es),
        Variant("nerdaxe_nerdos-1.1.0", "NerdAxe", nerd, 1, false, true, 485 to 1200, esb),
        Variant("nerdaxe-gamma_nerdos-1.1.0", "NerdAxeGamma", nerd, 1, false, true, 515 to 1150, esb),
        Variant("nerdaxe-gaia_nerdos-1.1.0", "NerdAxeGaia", nerd, 1, false, true, 350 to 940, listOf("Standard", "Boost")),
        Variant("nerdqaxe-plus_nerdos-1.1.0", "NerdQAxe+", nerd, 4, false, true, 490 to 1250, esb),
        Variant("nerdqaxe-plusplus_nerdos-1.1.0", "NerdQAxe++", nerd, 4, false, true, 600 to 1150, esb),
        Variant("nerdqaxe-plusplus_nerdos-legacy", "NerdQAxe++", nerd, 4, false, false, 600 to 1150, esb),
        Variant("nerdqx_nerdos-1.1.0", "NerdQX", nerd, 4, false, true, 777 to 1200, es),
        Variant("nerdhaxe-gamma_nerdos-1.1.0", "NerdHaxe-γ", nerd, 6, false, true, 600 to 1150, esb),
        Variant("nerdoctaxe-gamma_nerdos-1.1.0", "NerdOCTAXE-γ", nerd, 8, false, true, 700 to 1210, es),
        Variant("nerdoctaxe-plus_nerdos-1.1.0", "NerdOCTAXE+", nerd, 8, false, true, 490 to 1250, esb),
        Variant("nerdeko_nerdos-1.1.0", "NerdEKO", nerd, 12, false, true, 600 to 1150, esb),
        Variant("q1370_nerdos-1.1.0", "Q1370", nerd, 4, false, true, 600 to 1150, esb),
        // Factory clock 425 MHz is already above the highest BM1373 step (410), so there is no boost.
        Variant("q1373_nerdos-1.1.0", "Q1373", nerd, 4, false, true, 425 to 1010, es)
    )

    @Test fun everyVariantFileIsCovered() {
        val dir = java.io.File(Fixtures::class.java.classLoader!!.getResource("variants")!!.toURI())
        val files = dir.listFiles()!!.filter { it.name.endsWith(".json") }.map { it.name.removeSuffix(".json") }.toSet()
        assertEquals(files, all.map { it.file }.toSet())
    }

    @Test fun identification() = all.forEach { v ->
        val info = Fixtures.variant(v.file)
        assertEquals(v.file, v.firmware, info.firmware)
        assertEquals(v.file, v.title, info.detectedHardwareTitle)
        assertEquals(v.file, v.chips, info.asicCount)
    }

    @Test fun poolHandling() = all.forEach { v ->
        val miner = Fixtures.device(Fixtures.variant(v.file))
        val form = MinerSettingsForm.from(miner)
        assertEquals(v.file, if (v.slots) MinerSettingsForm.PoolApi.SLOTS else MinerSettingsForm.PoolApi.LEGACY, form.poolApi)
        assertEquals(v.file, v.dualPool, miner.supportsDualPool)
        assertNull(v.file, form.poolValidationMessage)
        val p = form.poolPayload(miner.supportsDualPool)
        if (v.slots) {
            assertFalse(v.file, p.pools.isNullOrEmpty()); assertNull(v.file, p.stratumURL)
        } else {
            assertNull(v.file, p.pools); assertFalse(v.file, p.stratumURL.isNullOrEmpty())
        }
        // AxeOS has no dual-pool mode: never send its fields there.
        assertEquals(v.file, v.dualPool, p.poolMode != null)
    }

    @Test fun clockProfiles() = all.forEach { v ->
        val miner = Fixtures.device(Fixtures.variant(v.file))
        val presets = miner.recommendedPresets
        assertEquals(v.file, v.presets, presets.map { it.name })
        val standard = presets.first { it.name == "Standard" }
        assertEquals(v.file, v.standard, standard.frequencyMHz to standard.coreVoltageMV)
        presets.forEach {
            assertTrue(v.file, it.frequencyMHz in miner.frequencyRange)
            assertTrue(v.file, it.coreVoltageMV in miner.coreVoltageRange)
            assertTrue(v.file, it.subtitle.startsWith("≈ "))
        }
        presets.firstOrNull { it.name == "Eco" }?.let { assertTrue(v.file, it.frequencyMHz < standard.frequencyMHz && it.coreVoltageMV < standard.coreVoltageMV) }
        presets.firstOrNull { it.name == "Boost" }?.let { assertTrue(v.file, it.frequencyMHz > standard.frequencyMHz && it.coreVoltageMV > standard.coreVoltageMV) }
    }

    @Test fun expectedHashrateMatchesReportedHashrate() = all.forEach { v ->
        val info = Fixtures.variant(v.file)
        val expected = info.expectedHashrateGHs(info.frequencyMHz)!!
        // The generator reports 97 % of the theoretical hashrate, like a healthy miner.
        assertTrue(v.file, abs(info.hashRate / expected - 0.97) < 0.01)
    }

    @Test fun boardVersions() {
        listOf("2.2" to "Bitaxe Max", "0.11" to "Bitaxe Ultra", "207" to "Bitaxe Ultra", "303" to "Bitaxe Hex",
            "403" to "Bitaxe Supra", "603" to "Bitaxe Gamma", "650" to "Bitaxe Gamma Duo", "702" to "Bitaxe Supra Hex",
            "801" to "Bitaxe GT", "1201" to "Bitaxe Naja Duo", "1300" to "Bitaxe Gamma Hex")
            .forEach { (board, family) -> assertEquals(board, family, BitaxeFamily.forBoard(board)?.name) }
    }

    @Test fun nerdOSModelsWithoutChipCount() {
        listOf(Triple("NerdQAxePlusPlus", "NerdQAxe++", 4), Triple("NerdQAxePlus", "NerdQAxe+", 4),
            Triple("NerdOCTAXE-γ", "NerdOCTAXE-γ", 8), Triple("NerdAxe", "NerdAxe", 1), Triple("SomeNewNerd", "SomeNewNerd", 1))
            .forEach { (model, title, chips) ->
                val info = Fixtures.info(mapOf("deviceModel" to model, "hashRate" to 1000, "ASICModel" to "BM1370"))
                assertEquals(MinerFirmware.NERD_OS, info.firmware)
                assertEquals(title, info.detectedHardwareTitle)
                assertEquals(chips, info.asicCount)
            }
    }

    @Test fun unknownBoardFallsBackToAsic() {
        val info = Fixtures.bitaxeInfo(mapOf("boardVersion" to "999"))
        assertEquals(MinerFirmware.AXE_OS, info.firmware)
        assertEquals("Bitaxe (BM1370 v999)", info.detectedHardwareTitle)
        assertEquals(AsicTuning.BM1370, info.asicTuning)
    }

    @Test fun unknownAsicHasNoProfilesButKeepsDefaultRanges() {
        val miner = Fixtures.device(Fixtures.bitaxeInfo(mapOf("boardVersion" to "999", "ASICModel" to "BM9999")))
        assertTrue(miner.recommendedPresets.isEmpty())
        assertEquals(MinerSettingsForm.frequencyRange, miner.frequencyRange)
    }
}
