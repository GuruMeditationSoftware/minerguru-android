package app.minerguru.model

import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A small local log for finding problems after days of real use: states, alerts, writes to miners and
 * regular measurements. It leaves the device only when the user exports it, so it holds no passwords,
 * wallet addresses, pool users, one-time codes or names, only the miners' addresses on the local network.
 */
class DiagnosticLog(private var file: File? = null) {
    private val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)

    /** The running app turns the log on; tests write nothing. Installs the crash note once. */
    @Synchronized
    fun activate(folder: File) {
        if (file != null) return
        file = File(folder, FILE_NAME)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            write("crash", "${e.javaClass.name} at ${e.stackTrace.take(5).joinToString(" < ")}")
            previous?.uncaughtException(thread, e)
        }
    }

    @Synchronized
    fun write(category: String, message: String, now: Long = System.currentTimeMillis()) {
        val target = file ?: return
        val line = "${format.format(Date(now))} [$category] ${message.replace('\n', ' ')}\n"
        runCatching {
            target.parentFile?.mkdirs()
            if (target.length() + line.length > MAX_BYTES) {
                val older = older(target)
                older.delete()
                target.renameTo(older)
            }
            target.appendText(line)
        }
    }

    /** Everything still on disk, oldest first. */
    @Synchronized
    fun exportText(): String {
        val target = file ?: return ""
        return listOf(older(target), target).filter { it.exists() }.joinToString("") { it.readText() }
    }

    private fun older(file: File) = File(file.parentFile, "minerguru.1.log")

    companion object {
        val shared = DiagnosticLog()
        /** Two files of this size at most: the current one and the one before it. */
        const val MAX_BYTES = 1_000_000L
        const val FILE_NAME = "minerguru.log"

        /** Model, firmware and the names (never the values) of the fields a miner reports. */
        fun profile(miner: MinerDevice, info: MinerSystemInfo): String {
            val keys = runCatching { JSONObject(miner.rawJSON ?: "{}").keys().asSequence().toList().sorted() }.getOrDefault(emptyList())
            return "model=${info.detectedHardwareTitle} firmware=${info.firmwareVersion} axeos=${info.axeOSVersion ?: "-"} keys=${keys.joinToString(",")}"
        }
    }
}
