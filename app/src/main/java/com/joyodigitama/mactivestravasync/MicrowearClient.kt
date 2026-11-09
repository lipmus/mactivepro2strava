package com.joyodigitama.mactivestravasync

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Thrown when the Microwear cloud rejects our stored tokenID. */
class MicrowearAuthException(message: String) : Exception(message)

/**
 * Talks to the Microwear cloud that MActivePro itself syncs workouts to.
 * Reaching the cloud avoids Android 14+ scoped storage, which blocks third-party apps
 * from reading /sdcard/Android/data/com.njj.mactivepro/.
 *
 * The cloud tokenID expires every few weeks. Instead of asking the user to dig a token
 * out of MActivePro's logs each time, [login] silently re-authenticates with the stored
 * account credentials (cmdID 2002) and caches the fresh token.
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

    private fun post(payload: JSONObject): JSONObject {
        val request = Request.Builder()
            .url(URL)
            .post(payload.toString().toRequestBody(jsonType))
            .header("User-Agent", "okhttp/4.12.0")
            .build()

        return http.newCall(request).execute().use { response ->
            val body = response.body?.string()
                ?: throw IllegalStateException("Cloud Microwear tidak merespons (kode " + response.code + ")")
            JSONObject(body)
        }
    }

    /** Logs in with the stored account, returns a fresh tokenID and persists it. */
    fun login(): String {
        val email = config.microwearEmail
        val hash = config.microwearPasswordHash
        if (email.isBlank() || hash.isBlank()) {
            throw MicrowearAuthException(
                "Token kedaluwarsa dan login otomatis belum disetel. " +
                    "Isi Email + password MD5 Microwear di Pengaturan."
            )
        }

        val root = post(JSONObject().apply {
            put("deviceType", 100)
            put("msgType", 0)
            put("cmdID", 2002)
            put("data", JSONObject().apply {
                put("password", hash)
                put("phoneNo", email)
            })
            put("osType", 4)
            put("lang", "en")
            put("version", "2.0.2")
            put("mac", "")
        })

        if (!root.optBoolean("success", false)) {
            val msg = root.optString("messsage").ifBlank { "Login Microwear gagal" }
            throw MicrowearAuthException(msg)
        }

        val token = root.optJSONObject("data")?.optString("token").orEmpty()
        if (token.isBlank()) throw MicrowearAuthException("Login berhasil tapi token kosong")

        config.microwearToken = token
        return token
    }

    /** Returns a token that the cloud should accept. */
    private fun ensureToken(): String = config.microwearToken.ifBlank { login() }

    /**
     * Fetches workouts for the date range.
     * On an expired token the cloud answers success=false ("You have logged out"),
     * so we log in again and retry once.
     */
    fun fetchWorkouts(startDate: String, endDate: String): List<Workout> {
        var workouts = fetchWithToken(ensureToken(), startDate, endDate)

        if (workouts == null) {
            workouts = fetchWithToken(login(), startDate, endDate)
        }

        return workouts ?: throw MicrowearAuthException("Gagal memuat data setelah login ulang")
    }

    /** Returns null when the token was rejected, so the caller can retry after a login. */
    private fun fetchWithToken(token: String, startDate: String, endDate: String): List<Workout>? {
        val root = post(JSONObject().apply {
            put("deviceType", 100)
            put("msgType", 0)
            put("cmdID", 2202)
            put("tokenID", token)
            put("data", JSONObject().apply {
                put("updateTime", startDate)
                put("endUpdateTime", endDate)
                put("source", "0")
            })
            put("osType", 4)
            put("lang", "en")
            put("version", "2.0.2")
            put("mac", config.watchMac)
        })

        if (!root.optBoolean("success", false)) {
            val msg = root.optString("messsage").ifBlank { root.optString("message") }
            if (msg.contains("logged out", ignoreCase = true) ||
                msg.contains("log in", ignoreCase = true)
            ) {
                return null   // signal: retry with a fresh token
            }
            throw MicrowearAuthException(msg.ifBlank { "Cloud Microwear menolak permintaan" })
        }

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

    /** Verifies credentials without mutating stored state. Used by the Settings screen. */
    fun testLogin(email: String, passwordHash: String): String {
        val root = post(JSONObject().apply {
            put("deviceType", 100)
            put("msgType", 0)
            put("cmdID", 2002)
            put("data", JSONObject().apply {
                put("password", passwordHash)
                put("phoneNo", email)
            })
            put("osType", 4)
            put("lang", "en")
            put("version", "2.0.2")
            put("mac", "")
        })
        if (!root.optBoolean("success", false)) {
            throw MicrowearAuthException(root.optString("messsage").ifBlank { "Login gagal" })
        }
        return root.optJSONObject("data")?.optString("token").orEmpty()
    }

    companion object {
        private const val URL = "http://api.microwear.com/api/"
    }
}
