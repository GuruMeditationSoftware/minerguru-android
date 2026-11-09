package app.minerguru.model

/** Firmware family of a miner. Decides which API fields exist and how settings are written. */
enum class MinerFirmware {
    /** ESP-Miner / AxeOS (all Bitaxe boards). Always reports `boardVersion`. */
    AXE_OS,
    /** ESP-Miner-NerdQAxePlus / NerdOS (NerdAxe, NerdQAxe, NerdOCTAXE, …). Reports `deviceModel`, no `boardVersion`. */
    NERD_OS
}

/**
 * Factory clock and the steps the firmware offers for one ASIC configuration
 * (ESP-Miner `AsicConfig`: `default_frequency_mhz`, `frequency_options`, `voltage_options`).
 */
data class AsicTuning(
    val defaultFrequencyMHz: Int,
    val defaultVoltageMV: Int,
    val frequencyOptions: List<Int>,
    val voltageOptions: List<Int>
) {
    data class Preset(val name: String, val frequencyMHz: Int, val voltageMV: Int)

    /** Eco, standard and boost: the firmware steps next to the standard clock and voltage. */
    fun presets(standardFrequencyMHz: Int? = null, standardVoltageMV: Int? = null): List<Preset> {
        val frequency = standardFrequencyMHz ?: defaultFrequencyMHz
        val voltage = standardVoltageMV ?: defaultVoltageMV
        val result = mutableListOf<Preset>()
        val ecoF = frequencyOptions.lastOrNull { it.toDouble() <= frequency * 0.95 }
        val ecoV = voltageOptions.lastOrNull { it < voltage }
        if (ecoF != null && ecoV != null) result += Preset("Eco", ecoF, ecoV)
        result += Preset("Standard", frequency, voltage)
        val boostF = frequencyOptions.firstOrNull { it.toDouble() >= frequency * 1.04 }
        val boostV = voltageOptions.firstOrNull { it > voltage }
        if (boostF != null && boostV != null) result += Preset("Boost", boostF, boostV)
        return result
    }

    companion object {
        val BM1397 = AsicTuning(425, 1400, listOf(400, 425, 450, 475, 485, 500, 525, 550, 575, 600),
            listOf(1100, 1150, 1200, 1250, 1300, 1350, 1400, 1450, 1500))
        val BM1366 = AsicTuning(485, 1200, listOf(400, 425, 450, 475, 485, 500, 525, 550, 575),
            listOf(1100, 1150, 1200, 1250, 1300))
        val BM1368 = AsicTuning(490, 1166, listOf(400, 425, 450, 475, 485, 490, 500, 525, 550, 575),
            listOf(1100, 1150, 1166, 1200, 1250, 1300))
        val BM1370 = AsicTuning(525, 1150, listOf(400, 490, 525, 550, 600, 625, 690),
            listOf(1000, 1060, 1100, 1150, 1200, 1250))
        /** Gamma Duo runs the BM1370 at lower clocks. */
        val BM1370_XP = AsicTuning(400, 1150, listOf(350, 375, 380, 400, 410),
            listOf(1000, 1060, 1100, 1150, 1200, 1250))
        /** Gamma Hex ships at the top BM1370 clock. */
        val BM1370_HEX = AsicTuning(690, 1200, listOf(400, 490, 525, 550, 600, 625, 690),
            listOf(1000, 1060, 1100, 1150, 1200, 1250))
        val BM1373 = AsicTuning(327, 1000, listOf(327, 350, 375, 380, 400, 410),
            listOf(1000, 1060, 1100, 1150, 1200, 1250))

        /** Tuning for an `ASICModel` string (`"BM1370"`, `"BM1372/BM1373"`, …). */
        fun forAsic(model: String): AsicTuning? {
            val upper = model.uppercase()
            return when {
                upper.contains("1373") || upper.contains("1372") -> BM1373
                upper.contains("1370") -> BM1370
                upper.contains("1368") -> BM1368
                upper.contains("1366") -> BM1366
                upper.contains("1397") -> BM1397
                else -> null
            }
        }
    }
}

/** Bitaxe board families from ESP-Miner's `device_config.h`; revisions of a family share a number range. */
data class BitaxeFamily(val name: String, val asicCount: Int, val tuning: AsicTuning?) {
    companion object {
        val MAX = BitaxeFamily("Bitaxe Max", 1, null)
        val ULTRA = BitaxeFamily("Bitaxe Ultra", 1, null)
        val HEX = BitaxeFamily("Bitaxe Hex", 6, null)
        val SUPRA = BitaxeFamily("Bitaxe Supra", 1, null)
        val GAMMA = BitaxeFamily("Bitaxe Gamma", 1, null)
        val GAMMA_DUO = BitaxeFamily("Bitaxe Gamma Duo", 2, AsicTuning.BM1370_XP)
        val SUPRA_HEX = BitaxeFamily("Bitaxe Supra Hex", 6, null)
        val GAMMA_TURBO = BitaxeFamily("Bitaxe GT", 2, null)
        val NAJA_DUO = BitaxeFamily("Bitaxe Naja Duo", 2, null)
        val GAMMA_HEX = BitaxeFamily("Bitaxe Gamma Hex", 6, AsicTuning.BM1370_HEX)

        /** Family for an AxeOS `boardVersion` such as `"601"`, `"302"` or the early `"2.2"` / `"0.11"`. */
        fun forBoard(boardVersion: String): BitaxeFamily? {
            val trimmed = boardVersion.trim()
            if (trimmed == "2.2") return MAX
            if (trimmed == "0.11") return ULTRA
            val n = trimmed.toIntOrNull() ?: return null
            return when (n) {
                in 100 until 200 -> MAX
                in 200 until 300 -> ULTRA
                in 300 until 400 -> HEX
                in 400 until 500 -> SUPRA
                in 600 until 650 -> GAMMA
                in 650 until 700 -> GAMMA_DUO
                in 700 until 800 -> SUPRA_HEX
                in 800 until 900 -> GAMMA_TURBO
                in 1200 until 1300 -> NAJA_DUO
                in 1300 until 1400 -> GAMMA_HEX
                else -> null
            }
        }
    }
}

/** NerdOS boards (`m_deviceModel`, `m_asicCount`); older NerdOS spelled names differently and had no chip count. */
data class NerdOSModel(val name: String, val asicCount: Int) {
    companion object {
        private val table: List<Pair<List<String>, NerdOSModel>> = listOf(
            listOf("nerdqaxeplusplus", "nerdqaxe++") to NerdOSModel("NerdQAxe++", 4),
            listOf("nerdqaxeplus", "nerdqaxe+") to NerdOSModel("NerdQAxe+", 4),
            listOf("nerdoctaxe") to NerdOSModel("", 8),
            listOf("nerdhaxe") to NerdOSModel("", 6),
            listOf("nerdeko") to NerdOSModel("", 12),
            listOf("nerdqx", "q1370", "q1373", "nerdqaxe") to NerdOSModel("", 4),
            listOf("nerdaxe") to NerdOSModel("", 1)
        )

        /** Known model for a reported `deviceModel`; an empty `name` keeps the reported spelling. */
        fun forDeviceModel(deviceModel: String): NerdOSModel? {
            val lower = deviceModel.lowercase().trim()
            return table.firstOrNull { (prefixes, _) -> prefixes.any { lower.startsWith(it) } }?.second
        }
    }
}
