package app.minerguru.service

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/** Battery-saver monitoring: WorkManager runs this about every 15 minutes. */
class MonitorWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        MinerMonitor.pollOnce(applicationContext, retries = RETRIES)
        return Result.success()
    }

    private companion object {
        /** Two more polls 45 s apart: offline needs three failed polls. */
        const val RETRIES = 2
    }
}
