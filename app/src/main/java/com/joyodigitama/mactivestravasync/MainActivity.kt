package com.joyodigitama.mactivestravasync

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.lifecycle.lifecycleScope
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var config: SyncConfig
    private lateinit var tvLog: TextView
    private lateinit var pbSync: ProgressBar
    private lateinit var svLog: ScrollView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        config = SyncConfig(this)
        tvLog = findViewById(R.id.tv_log)
        pbSync = findViewById(R.id.pb_sync)
        svLog = findViewById(R.id.sv_log)

        val swAuto = findViewById<SwitchCompat>(R.id.sw_auto_sync)
        swAuto.isChecked = config.autoSyncEnabled

        findViewById<Button>(R.id.btn_sync_now).setOnClickListener { runSync() }
        findViewById<Button>(R.id.btn_open_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        swAuto.setOnCheckedChangeListener { _, isChecked ->
            config.autoSyncEnabled = isChecked
            if (isChecked) schedulePeriodic() else WorkManager.getInstance(this).cancelUniqueWork(WORK_NAME)
            append("\n[Otomatis] ${if (isChecked) "Aktif — cek tiap 1 jam" else "Dimatikan"}")
        }

        if (config.autoSyncEnabled) schedulePeriodic()
    }

    private fun schedulePeriodic() {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(1, TimeUnit.HOURS).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    private fun append(line: String) {
        tvLog.append(line + "\n")
        svLog.post { svLog.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun runSync() {
        pbSync.visibility = ProgressBar.VISIBLE
        tvLog.text = ""

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { syncAll() }
            pbSync.visibility = ProgressBar.GONE
            Toast.makeText(this@MainActivity, result, Toast.LENGTH_LONG).show()
        }
    }

    /** Returns a one-line summary for the toast. */
    private suspend fun syncAll(): String {
        val microwear = MicrowearClient(config)
        val strava = StravaClient(config)

        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val cal = Calendar.getInstance()
        val endStr = sdf.format(cal.time)
        cal.add(Calendar.DAY_OF_YEAR, -30)
        val startStr = sdf.format(cal.time)

        return try {
            withContext(Dispatchers.Main) { append("Memuat token Strava…") }
            val token = strava.ensureFreshToken()
            withContext(Dispatchers.Main) { append("Token siap.") }

            withContext(Dispatchers.Main) { append("Mengambil data dari cloud Microwear ($startStr s.d. $endStr)…") }
            val workouts = microwear.fetchWorkouts(startStr, endStr)
            withContext(Dispatchers.Main) { append("Ditemukan ${workouts.size} sesi olahraga.") }

            var uploaded = 0
            var skipped = 0

            for (w in workouts.sortedBy { it.dateTime }) {
                val key = if (w.timeStamp > 0) w.timeStamp.toString() else w.dateTime

                if (config.isUploaded(key)) {
                    skipped++
                    withContext(Dispatchers.Main) { append("• Lewati (sudah ada): ${w.dateTime}") }
                    continue
                }

                val build = TcxBuilder.build(w)
                if (build == null) {
                    skipped++
                    withContext(Dispatchers.Main) { append("• Lewati (tanpa GPS): ${w.dateTime}") }
                    continue
                }

                withContext(Dispatchers.Main) {
                    append("• Mengunggah ${build.activityName} (${build.pointCount} titik)…")
                }

                try {
                    val res = strava.uploadTcx(
                        token = token,
                        tcx = build.tcxBytes,
                        name = build.activityName,
                        activityType = build.sportType,
                        externalId = "microwear-$key"
                    )
                    if (res.activityId != null) {
                        config.markUploaded(key, res.activityId)
                        uploaded++
                        withContext(Dispatchers.Main) {
                            append("  ✓ strava.com/activities/${res.activityId}")
                        }
                    } else if (res.status.contains("duplicate", ignoreCase = true)) {
                        config.markUploaded(key, 0L)
                        skipped++
                        withContext(Dispatchers.Main) { append("  ↺ sudah ada di Strava") }
                    } else {
                        withContext(Dispatchers.Main) { append("  ! ${res.status}") }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { append("  ✗ ${e.message?.take(160)}") }
                }
            }

            "$uploaded terkirim, $skipped dilewati"
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { append("GAGAL: ${e.message?.take(200)}") }
            "Gagal: ${e.message?.take(80)}"
        }
    }

    companion object {
        private const val WORK_NAME = "mactive_strava_periodic_sync"
    }
}
