package com.joyodigitama.mactivestravasync

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
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
import com.google.android.material.datepicker.MaterialDatePicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var config: SyncConfig
    private lateinit var tvDateRange: TextView
    private lateinit var tvItemCount: TextView
    private lateinit var tvLog: TextView
    private lateinit var pbSync: ProgressBar
    private lateinit var svLog: ScrollView
    private lateinit var llWorkoutList: LinearLayout
    private lateinit var btnSyncNow: Button
    private lateinit var cbIncludeNoGps: CheckBox
    private lateinit var btnScan: Button

    private val logBuffer = StringBuilder()

    private var startDateStr: String = ""
    private var endDateStr: String = ""

    private data class WorkoutItem(
        val workout: MicrowearClient.Workout,
        val key: String,
        val hasGps: Boolean,
        val pointCount: Int,
        var isChecked: Boolean,
        val isAlreadyUploaded: Boolean
    )

    private val currentItems = ArrayList<WorkoutItem>()

    private val apiDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("Asia/Jakarta")
    }
    private val displayDateFormat = SimpleDateFormat("dd MMM yyyy", Locale("id", "ID")).apply {
        timeZone = TimeZone.getTimeZone("Asia/Jakarta")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        config = SyncConfig(this)
        tvDateRange = findViewById(R.id.tv_date_range)
        tvItemCount = findViewById(R.id.tv_item_count)
        tvLog = findViewById(R.id.tv_log)
        pbSync = findViewById(R.id.pb_sync)
        svLog = findViewById(R.id.sv_log)
        llWorkoutList = findViewById(R.id.ll_workout_list)
        btnSyncNow = findViewById(R.id.btn_sync_now)
        cbIncludeNoGps = findViewById(R.id.cb_include_no_gps)
        btnScan = findViewById(R.id.btn_scan)

        val swAuto = findViewById<SwitchCompat>(R.id.sw_auto_sync)
        swAuto.isChecked = config.autoSyncEnabled

        setPresetToday()

        findViewById<Button>(R.id.btn_preset_today).setOnClickListener { setPresetToday() }
        findViewById<Button>(R.id.btn_preset_7days).setOnClickListener { setPreset7Days() }
        findViewById<Button>(R.id.btn_custom_date).setOnClickListener { pickCustomDateRange() }

        btnScan.setOnClickListener { runScan() }
        btnSyncNow.setOnClickListener { runSyncSelected() }

        cbIncludeNoGps.setOnCheckedChangeListener { _, isChecked ->
            for (item in currentItems) {
                if (!item.hasGps && !item.isAlreadyUploaded) {
                    item.isChecked = isChecked
                }
            }
            renderWorkoutList()
            updateSyncButtonText()
        }

        findViewById<Button>(R.id.btn_open_settings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        findViewById<Button>(R.id.btn_copy_log).setOnClickListener { copyLogToClipboard() }

        config.lastLog.takeIf { it.isNotBlank() }?.let {
            logBuffer.append(it)
            tvLog.text = it
            svLog.post { svLog.fullScroll(ScrollView.FOCUS_DOWN) }
        }

        swAuto.setOnCheckedChangeListener { _, isChecked ->
            config.autoSyncEnabled = isChecked
            if (isChecked) schedulePeriodic() else WorkManager.getInstance(this).cancelUniqueWork(WORK_NAME)
            append("[Otomatis] " + (if (isChecked) "Aktif - sync hari ini tiap 1 jam" else "Dimatikan"))
        }

        if (config.autoSyncEnabled) schedulePeriodic()

        runScan()
    }

    private fun setPresetToday() {
        val today = Calendar.getInstance().time
        startDateStr = apiDateFormat.format(today)
        endDateStr = startDateStr
        tvDateRange.text = "Hari Ini (" + displayDateFormat.format(today) + ")"
        findViewById<Button>(R.id.btn_preset_today).setBackgroundColor(Color.parseColor("#2E7D32"))
        findViewById<Button>(R.id.btn_preset_7days).setBackgroundColor(Color.parseColor("#333333"))
        findViewById<Button>(R.id.btn_custom_date).setBackgroundColor(Color.parseColor("#333333"))
    }

    private fun setPreset7Days() {
        val cal = Calendar.getInstance()
        val end = cal.time
        cal.add(Calendar.DAY_OF_YEAR, -6)
        val start = cal.time
        startDateStr = apiDateFormat.format(start)
        endDateStr = apiDateFormat.format(end)
        tvDateRange.text = "7 Hari: " + displayDateFormat.format(start) + " - " + displayDateFormat.format(end)
        findViewById<Button>(R.id.btn_preset_today).setBackgroundColor(Color.parseColor("#333333"))
        findViewById<Button>(R.id.btn_preset_7days).setBackgroundColor(Color.parseColor("#2E7D32"))
        findViewById<Button>(R.id.btn_custom_date).setBackgroundColor(Color.parseColor("#333333"))
    }

    private fun pickCustomDateRange() {
        val picker = MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText("Pilih Rentang Tanggal")
            .build()
        picker.addOnPositiveButtonClickListener { selection ->
            val from = selection.first
            val to = selection.second
            if (from != null && to != null) {
                val start = Date(from)
                val end = Date(to)
                startDateStr = apiDateFormat.format(start)
                endDateStr = apiDateFormat.format(end)
                tvDateRange.text = displayDateFormat.format(start) + " - " + displayDateFormat.format(end)
                findViewById<Button>(R.id.btn_preset_today).setBackgroundColor(Color.parseColor("#333333"))
                findViewById<Button>(R.id.btn_preset_7days).setBackgroundColor(Color.parseColor("#333333"))
                findViewById<Button>(R.id.btn_custom_date).setBackgroundColor(Color.parseColor("#2E7D32"))
                runScan()
            }
        }
        picker.show(supportFragmentManager, "DATE_RANGE_PICKER")
    }

    private fun runScan() {
        pbSync.visibility = ProgressBar.VISIBLE
        btnScan.isEnabled = false
        append("")
        append("Memindai sesi olahraga (" + startDateStr + " s.d. " + endDateStr + ")...")

        lifecycleScope.launch {
            val microwear = MicrowearClient(config)
            val workouts = withContext(Dispatchers.IO) {
                try {
                    microwear.fetchWorkouts(startDateStr, endDateStr)
                } catch (e: Exception) {
                    Log.e(TAG, "Scan failed", e)
                    emptyList()
                }
            }

            pbSync.visibility = ProgressBar.GONE
            btnScan.isEnabled = true

            currentItems.clear()
            val includeNoGps = cbIncludeNoGps.isChecked

            for (w in workouts.sortedByDescending { it.dateTime }) {
                val key = if (w.timeStamp > 0) w.timeStamp.toString() else w.dateTime
                val pts = TcxBuilder.gpsPointCount(w)
                val hasGps = pts >= 10
                val already = config.isUploaded(key)
                val check = !already && (hasGps || includeNoGps)
                currentItems.add(
                    WorkoutItem(
                        workout = w,
                        key = key,
                        hasGps = hasGps,
                        pointCount = pts,
                        isChecked = check,
                        isAlreadyUploaded = already
                    )
                )
            }

            append("Ditemukan " + currentItems.size + " sesi olahraga.")
            renderWorkoutList()
            updateSyncButtonText()
        }
    }

    private fun renderWorkoutList() {
        llWorkoutList.removeAllViews()

        if (currentItems.isEmpty()) {
            val empty = TextView(this).apply {
                text = "Tidak ada aktivitas pada rentang tanggal ini."
                setTextColor(Color.parseColor("#777777"))
                textSize = 12f
                setPadding(20, 40, 20, 40)
                gravity = android.view.Gravity.CENTER
            }
            llWorkoutList.addView(empty)
            tvItemCount.text = "0 sesi"
            return
        }

        tvItemCount.text = currentItems.size.toString() + " sesi"
        val inflater = LayoutInflater.from(this)

        for (item in currentItems) {
            val view = inflater.inflate(R.layout.workout_item, llWorkoutList, false)
            val cb = view.findViewById<CheckBox>(R.id.cb_select)
            val tvTitle = view.findViewById<TextView>(R.id.tv_title)
            val tvDetail = view.findViewById<TextView>(R.id.tv_detail)
            val tvBadge = view.findViewById<TextView>(R.id.tv_badge)
            val tvStatus = view.findViewById<TextView>(R.id.tv_status)

            val w = item.workout
            val isBike = w.type == 3 || w.type == 142 || w.type == 143
            val typeName = if (isBike) "Bersepeda" else if (w.type == 2 || w.type == 141) "Jalan" else "Lari"
            val distKm = String.format(Locale.US, "%.2f km", w.distance / 1000.0)
            val durMin = (w.duration / 60).toString() + "m " + (w.duration % 60) + "s"
            val calText = w.calories.toInt().toString() + " kkal"

            tvTitle.text = typeName + " - " + w.dateTime
            tvDetail.text = distKm + "  |  " + durMin + "  |  " + calText

            if (item.hasGps) {
                tvBadge.text = "Ada GPS (" + item.pointCount + " titik)"
                tvBadge.setTextColor(Color.parseColor("#4CAF50"))
            } else {
                tvBadge.text = "Tanpa GPS (Indoor)"
                tvBadge.setTextColor(Color.parseColor("#FF9800"))
            }

            if (item.isAlreadyUploaded) {
                tvStatus.text = "Sudah di Strava"
                tvStatus.setTextColor(Color.parseColor("#2196F3"))
                cb.isChecked = false
                cb.isEnabled = false
                view.alpha = 0.55f
            } else {
                tvStatus.text = "Belum Sync"
                tvStatus.setTextColor(Color.parseColor("#AAAAAA"))
                cb.isChecked = item.isChecked
                cb.isEnabled = true
                view.alpha = 1.0f
            }

            cb.setOnCheckedChangeListener { _, isChecked ->
                item.isChecked = isChecked
                updateSyncButtonText()
            }

            llWorkoutList.addView(view)
        }
    }

    private fun updateSyncButtonText() {
        val count = currentItems.count { it.isChecked && !it.isAlreadyUploaded }
        btnSyncNow.text = "SYNC TERPILIH (" + count + " SESI)"
        btnSyncNow.isEnabled = count > 0
        btnSyncNow.alpha = if (count > 0) 1.0f else 0.5f
    }

    private fun runSyncSelected() {
        val selected = currentItems.filter { it.isChecked && !it.isAlreadyUploaded }
        if (selected.isEmpty()) {
            Toast.makeText(this, "Pilih minimal 1 sesi untuk di-sync", Toast.LENGTH_SHORT).show()
            return
        }

        pbSync.visibility = ProgressBar.VISIBLE
        btnSyncNow.isEnabled = false

        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { syncSelectedWorkouts(selected) }
            pbSync.visibility = ProgressBar.GONE
            btnSyncNow.isEnabled = true
            Toast.makeText(this@MainActivity, result, Toast.LENGTH_LONG).show()
            runScan()
        }
    }

    private suspend fun syncSelectedWorkouts(targets: List<WorkoutItem>): String {
        val strava = StravaClient(config)

        return try {
            withContext(Dispatchers.Main) { append("Memuat token Strava...") }
            val token = strava.ensureFreshToken()
            withContext(Dispatchers.Main) { append("Token Strava siap.") }

            var uploaded = 0
            var skipped = 0

            for (item in targets) {
                val w = item.workout
                val build = TcxBuilder.build(w, allowNoGps = !item.hasGps)
                if (build == null) {
                    skipped++
                    withContext(Dispatchers.Main) { append("Lewati: " + w.dateTime) }
                    continue
                }

                withContext(Dispatchers.Main) {
                    val gpsNote = if (build.hasGps) build.pointCount.toString() + " titik" else "tanpa GPS"
                    append("Mengunggah " + build.activityName + " (" + gpsNote + ")...")
                }

                try {
                    val res = strava.uploadTcx(
                        token = token,
                        tcx = build.tcxBytes,
                        name = build.activityName,
                        activityType = build.sportType,
                        externalId = "microwear-" + item.key
                    )
                    if (res.activityId != null) {
                        config.markUploaded(item.key, res.activityId)
                        uploaded++
                        withContext(Dispatchers.Main) {
                            append("  OK strava.com/activities/" + res.activityId)
                        }
                    } else if (res.status.contains("duplicate", ignoreCase = true)) {
                        config.markUploaded(item.key, 0L)
                        skipped++
                        withContext(Dispatchers.Main) { append("  Sudah ada di Strava") }
                    } else {
                        withContext(Dispatchers.Main) { append("  ! " + res.status) }
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) { append("  GAGAL: " + (e.message?.take(160) ?: "")) }
                }
            }

            uploaded.toString() + " berhasil disinkronkan, " + skipped + " dilewati"
        } catch (e: Exception) {
            Log.e(TAG, "SyncSelected failed", e)
            val detail = e.message ?: e.javaClass.simpleName
            withContext(Dispatchers.Main) { append("GAGAL: " + detail) }
            "Gagal: " + detail
        }
    }

    private fun copyLogToClipboard() {
        val text = logBuffer.toString().ifBlank { "Belum ada log." }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("MActive Strava Sync Log", text))

        val saved = try {
            val dir = getExternalFilesDir(null)
            java.io.File(dir, "sync_log.txt").writeText(text)
            val publicDir = java.io.File("/sdcard/Download")
            if (publicDir.canWrite()) java.io.File(publicDir, "mactive_sync_log.txt").writeText(text)
            true
        } catch (e: Exception) {
            Log.w(TAG, "Gagal simpan log", e)
            false
        }

        Toast.makeText(
            this,
            if (saved) "Log disalin + /sdcard/Download/mactive_sync_log.txt"
            else "Log disalin ke clipboard",
            Toast.LENGTH_LONG
        ).show()
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
        Log.i(TAG, line)
        logBuffer.append(line).append('\n')
        tvLog.text = logBuffer.toString()
        svLog.post { svLog.fullScroll(ScrollView.FOCUS_DOWN) }
        config.lastLog = logBuffer.toString()
    }

    companion object {
        private const val TAG = "MActiveSync"
        private const val WORK_NAME = "mactive_strava_periodic_sync"
    }
}
