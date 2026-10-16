package com.joyodigitama.mactivestravasync

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class StravaClient(private val config: SyncConfig) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val formType = "application/x-www-form-urlencoded".toMediaType()

    /** The access token lives 6 h; the refresh token does not expire, so this renews silently. */
    fun ensureFreshToken(): String {
        val now = System.currentTimeMillis() / 1000
        if (config.stravaTokenExpiresAt - now > 300 && config.stravaAccessToken.isNotEmpty()) {
            return config.stravaAccessToken
        }

        val body = listOf(
            "client_id" to config.stravaClientId,
            "client_secret" to config.stravaClientSecret,
            "grant_type" to "refresh_token",
            "refresh_token" to config.stravaRefreshToken
        ).joinToString("&") { "${it.first}=${java.net.URLEncoder.encode(it.second, "UTF-8")}" }
            .toRequestBody(formType)

        val request = Request.Builder()
            .url("https://www.strava.com/oauth/token")
            .post(body)
            .build()

        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("Refresh token gagal (${response.code}): ${text.take(200)}")
            }
            val json = JSONObject(text)
            val access = json.getString("access_token")
            config.stravaAccessToken = access
            config.stravaTokenExpiresAt = json.getLong("expires_at")
            json.optString("refresh_token").takeIf { it.isNotEmpty() }?.let {
                config.stravaRefreshToken = it
            }
            return access
        }
    }

    data class UploadResult(val uploadId: Long, val activityId: Long?, val status: String)

    fun uploadTcx(
        token: String,
        tcx: ByteArray,
        name: String,
        activityType: String,
        externalId: String
    ): UploadResult {
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("data_type", "tcx")
            .addFormDataPart("name", name)
            .addFormDataPart("description", "Disinkronkan dari Watch Ultra3 (Realtek LE7)")
            .addFormDataPart("activity_type", activityType)
            .addFormDataPart("external_id", externalId)
            .addFormDataPart(
                "file", "activity.tcx",
                tcx.toRequestBody("application/vnd.garmin.tcx+xml".toMediaType())
            )
            .build()

        val request = Request.Builder()
            .url("https://www.strava.com/api/v3/uploads")
            .header("Authorization", "Bearer $token")
            .post(multipart)
            .build()

        val uploadId: Long
        http.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("Upload ditolak (${response.code}): ${text.take(250)}")
            }
            uploadId = JSONObject(text).getLong("id")
        }

        // Strava processes asynchronously; poll until it reports done or errors.
        repeat(25) {
            Thread.sleep(2000)
            val statusRequest = Request.Builder()
                .url("https://www.strava.com/api/v3/uploads/$uploadId")
                .header("Authorization", "Bearer $token")
                .build()
            http.newCall(statusRequest).execute().use { response ->
                val text = response.body?.string().orEmpty()
                val json = JSONObject(text)
                val status = json.optString("status", "")
                val activityId = json.optLong("activity_id", 0L).takeIf { it > 0 }

                if (activityId != null) {
                    return UploadResult(uploadId, activityId, status)
                }
                val error = json.optString("error", "")
                if (error.isNotEmpty()) {
                    // A duplicate means an earlier copy is already on Strava; treat as done.
                    return UploadResult(uploadId, null, "duplicate: ${error.take(120)}")
                }
                if (status.contains("ready", ignoreCase = true)) {
                    return UploadResult(uploadId, activityId, status)
                }
            }
        }
        return UploadResult(uploadId, null, "timeout menunggu Strava")
    }
}
