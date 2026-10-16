package com.joyodigitama.mactivestravasync

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Talks to the Microwear cloud that MActivePro itself syncs workouts to.
 * Reaching the cloud avoids Android 14+ scoped storage, which blocks third-party apps
 * from reading /sdcard/Android/data/com.njj.mactivepro/.
 */
class MicrowearClient(private val config: SyncConfig) {

    data class Workout(
        val timeStamp: Long,
        val dateTime: String,
        val duration: Int,
        val distance: Double,
        val calories: Double,
        val coordinateArr: String,
        val heartRateArr: String,
        val type: Int
    )

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json; charset=UTF-8".toMediaType()

    fun fetchWorkouts(startDate: String, endDate: String): List<Workout> {
        val payload = JSONObject().apply {
            put("deviceType", 100)
            put("msgType", 0)
            put("cmdID", 2202)
            put("tokenID", config.microwearToken)
            put("data", JSONObject().apply {
                put("updateTime", startDate)
                put("endUpdateTime", endDate)
                put("source", "0")
            })
            put("osType", 4)
            put("lang", "en")
            put("version", "2.0.2")
            put("mac", config.watchMac)
        }

        val request = Request.Builder()
            .url("http://api.microwear.com/api/")
            .post(payload.toString().toRequestBody(jsonType))
            .header("User-Agent", "okhttp/4.12.0")
            .build()

        http.newCall(request).execute().use { response ->
            val body = response.body?.string() ?: return emptyList()
            val root = JSONObject(body)
            if (!root.optBoolean("success", false)) return emptyList()

            val arr = root.optJSONArray("data") ?: return emptyList()
            val out = ArrayList<Workout>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    Workout(
                        timeStamp = o.optLong("timeStamp", 0L),
                        dateTime = o.optString("dateTime", ""),
                        duration = o.optInt("duration", 0),
                        distance = o.optDouble("distance", 0.0),
                        calories = o.optDouble("calories", 0.0),
                        coordinateArr = o.optString("coordinateArr", ""),
                        heartRateArr = o.optString("heartRateArr", ""),
                        type = o.optInt("type", 1)
                    )
                )
            }
            return out
        }
    }
}
