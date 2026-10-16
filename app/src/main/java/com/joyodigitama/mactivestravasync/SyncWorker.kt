package com.joyodigitama.mactivestravasync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class SyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val config = SyncConfig(applicationContext)
        val microwear = MicrowearClient(config)
        val strava = StravaClient(config)

        try {
            val token = strava.ensureFreshToken()

            val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val cal = Calendar.getInstance()
            val endStr = sdf.format(cal.time)
            cal.add(Calendar.DAY_OF_YEAR, -7)
            val startStr = sdf.format(cal.time)

            val workouts = microwear.fetchWorkouts(startStr, endStr)
            for (w in workouts) {
                val key = if (w.timeStamp > 0) w.timeStamp.toString() else w.dateTime
                if (config.isUploaded(key)) continue

                val build = TcxBuilder.build(w) ?: continue
                val res = strava.uploadTcx(
                    token = token,
                    tcx = build.tcxBytes,
                    name = build.activityName,
                    activityType = build.sportType,
                    externalId = "microwear-$key"
                )
                if (res.activityId != null || res.status.contains("duplicate", ignoreCase = true)) {
                    config.markUploaded(key, res.activityId ?: 0L)
                }
            }
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}
