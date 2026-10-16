package com.joyodigitama.mactivestravasync

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        val config = SyncConfig(this)

        val etClientId = findViewById<EditText>(R.id.et_client_id)
        val etClientSecret = findViewById<EditText>(R.id.et_client_secret)
        val etRefreshToken = findViewById<EditText>(R.id.et_refresh_token)
        val etMicrowearToken = findViewById<EditText>(R.id.et_microwear_token)
        val etWatchMac = findViewById<EditText>(R.id.et_watch_mac)
        val btnSave = findViewById<Button>(R.id.btn_save_settings)

        // Load existing
        etClientId.setText(config.stravaClientId)
        etClientSecret.setText(config.stravaClientSecret)
        etRefreshToken.setText(config.stravaRefreshToken)
        etMicrowearToken.setText(config.microwearToken)
        etWatchMac.setText(config.watchMac)

        btnSave.setOnClickListener {
            val cid = etClientId.text.toString().trim()
            val csec = etClientSecret.text.toString().trim()
            val ref = etRefreshToken.text.toString().trim()
            val mtok = etMicrowearToken.text.toString().trim()
            val mac = etWatchMac.text.toString().trim()

            if (cid.isEmpty() || csec.isEmpty() || ref.isEmpty() || mtok.isEmpty()) {
                Toast.makeText(this, "Semua field harus diisi!", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            config.stravaClientId = cid
            config.stravaClientSecret = csec
            config.stravaRefreshToken = ref
            config.microwearToken = mtok
            config.watchMac = mac

            // Force access token refresh on next sync
            config.stravaTokenExpiresAt = 0L

            Toast.makeText(this, "Pengaturan berhasil disimpan!", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
}
