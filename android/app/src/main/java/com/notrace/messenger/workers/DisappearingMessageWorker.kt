package com.notrace.messenger.workers

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.notrace.messenger.NoTraceApplication
import java.util.concurrent.TimeUnit

/**
 * Sweeps the `messages` table for anything past its `expires_at` and
 * deletes it — permanently, via SQLCipher's `secure_delete` pragma (see
 * NoTraceDatabase.onConfigure), not a soft "hide from UI" delete. This is
 * the enforcement half of the disappearing-messages feature; the other
 * half (computing `expires_at` at send/receive time from the
 * conversation's timer setting) lives in ChatDao.insertMessage.
 *
 * Runs at most every 15 minutes (WorkManager's minimum periodic interval)
 * — plenty tight for 1-day-minimum timers, and battery-friendly.
 */
class DisappearingMessageWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    companion object {
        private const val UNIQUE_WORK_NAME = "disappearing_message_sweep"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<DisappearingMessageWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }

    override suspend fun doWork(): Result {
        return try {
            val repository = (applicationContext as NoTraceApplication).container.messageRepository
            repository.purgeExpiredMessages()
            Result.success()
        } catch (t: Throwable) {
            Result.retry()
        }
    }
}
