package com.joyodigitama.mactivestravasync

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val config = SyncConfig(this)

        val etClientId = findViewById<EditText>(R.id.et_client_id)
        val etClientSecret = findViewById<EditText>(R.id.et_client_secret)
        val etRefreshToken = findViewById<EditText>(R.id.et_refresh_token)
        val etMicrowearToken = findViewById<EditText>(R.id.et_microwear_token)
        val etMicrowearEmail = findViewById<EditText>(R.id.et_microwear_email)
        val etMicrowearPassword = findViewById<EditText>(R.id.et_microwear_password)
        val etWatchMac = findViewById<EditText>(R.id.et_watch_mac)
        val btnSave = findViewById<Button>(R.id.btn_save_settings)
        val btnTestLogin = findViewById<Button>(R.id.btn_test_login)

        etClientId.setText(config.stravaClientId)
        etClientSecret.setText(config.stravaClientSecret)
        etRefreshToken.setText(config.stravaRefreshToken)
        etMicrowearToken.setText(config.microwearToken)
        etMicrowearEmail.setText(config.microwearEmail)
        etWatchMac.setText(config.watchMac)

        // Microwear accounts store an MD5, never the plain password, so we can only
        // re-derive it while the user is typing a new one here.
        btnTestLogin.setOnClickListener {
            val email = etMicrowearEmail.text.toString().trim()
            val raw = etMicrowearPassword.text.toString()
            val hash = if (raw.isBlank()) config.microwearPasswordHash else md5(raw)

            if (email.isBlank() || hash.isBlank()) {
                Toast.makeText(this, "Isi email dan password dulu", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnTestLogin.isEnabled = false
            lifecycleScope.launch {
                val msg = withContext(Dispatchers.IO) {
                    try {
                        val token = MicrowearClient(config).testLogin(email, hash)
                        config.microwearEmail = email
                        config.microwearPasswordHash = hash
                        config.microwearToken = token
                        "Login berhasil. Token baru tersimpan."
                    } catch (e: Exception) {
                        "Gagal: " + (e.message ?: e.javaClass.simpleName)
                    }
                }
                btnTestLogin.isEnabled = true
                Toast.makeText(this@SettingsActivity, msg, Toast.LENGTH_LONG).show()
            }
        }

        btnSave.setOnClickListener {
            val cid = etClientId.text.toString().trim()
            val csec = etClientSecret.text.toString().trim()
            val ref = etRefreshToken.text.toString().trim()
            val mtok = etMicrowearToken.text.toString().trim()
            val memail = etMicrowearEmail.text.toString().trim()
            val mpass = etMicrowearPassword.text.toString()
            val mac = etWatchMac.text.toString().trim()

            if (cid.isEmpty() || csec.isEmpty() || ref.isEmpty()) {
                Toast.makeText(this, "Strava Client ID, Secret, dan Refresh Token wajib diisi!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            config.stravaClientId = cid
            config.stravaClientSecret = csec
            config.stravaRefreshToken = ref
            config.watchMac = mac
            if (mtok.isNotEmpty()) config.microwearToken = mtok
            if (memail.isNotEmpty()) config.microwearEmail = memail
            if (mpass.isNotEmpty()) config.microwearPasswordHash = md5(mpass)

            config.stravaTokenExpiresAt = 0L

            Toast.makeText(this, "Pengaturan berhasil disimpan!", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5")
            .digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
