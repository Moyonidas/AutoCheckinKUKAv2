package com.kuka.autocheckin

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.text.TextUtils
import android.app.Activity
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : Activity() {

    companion object {
        const val PREFS_NAME = "AutoCheckinPrefs"
        const val KEY_EMAIL = "runa_email"
        const val KEY_PASSWORD = "runa_password"
        const val SMS_PERMISSION_CODE = 101
    }

    private lateinit var tvAccessibilityStatus: TextView
    private lateinit var btnAccessibility: Button
    private lateinit var etEmail: EditText
    private lateinit var etPassword: EditText
    private lateinit var btnSave: Button
    private lateinit var tvSaveStatus: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvAccessibilityStatus = findViewById(R.id.tvAccessibilityStatus)
        btnAccessibility = findViewById(R.id.btnAccessibility)
        etEmail = findViewById(R.id.etEmail)
        etPassword = findViewById(R.id.etPassword)
        btnSave = findViewById(R.id.btnSave)
        tvSaveStatus = findViewById(R.id.tvSaveStatus)

        // Cargar credenciales guardadas
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        etEmail.setText(prefs.getString(KEY_EMAIL, ""))
        etPassword.setText(prefs.getString(KEY_PASSWORD, ""))

        // Solicitar permiso SMS si no se tiene
        requestSmsPermission()

        // Botón accesibilidad
        btnAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }

        // Guardar credenciales
        btnSave.setOnClickListener {
            val email = etEmail.text.toString().trim()
            val password = etPassword.text.toString()

            if (email.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Ingresa email y contraseña", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            prefs.edit()
                .putString(KEY_EMAIL, email)
                .putString(KEY_PASSWORD, password)
                .apply()

            tvSaveStatus.text = "✅ Credenciales guardadas correctamente"
            Toast.makeText(this, "Credenciales guardadas", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityStatus()
    }

    private fun updateAccessibilityStatus() {
        val enabled = isAccessibilityServiceEnabled()
        if (enabled) {
            tvAccessibilityStatus.text = "✅ Servicio de Accesibilidad: ACTIVO"
            tvAccessibilityStatus.setTextColor(0xFF2E7D32.toInt())
            tvAccessibilityStatus.setBackgroundColor(0xFFF1F8E9.toInt())
        } else {
            tvAccessibilityStatus.text = "⚠️ Servicio de Accesibilidad: DESACTIVADO — Toca el botón de abajo para activarlo"
            tvAccessibilityStatus.setTextColor(0xFFCC0000.toInt())
            tvAccessibilityStatus.setBackgroundColor(0xFFFFF3F3.toInt())
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val service = packageName + "/" + RunaAccessibilityService::class.java.canonicalName
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabledServices.split(":").any { it.equals(service, ignoreCase = true) }
    }

    private fun requestSmsPermission() {
        val needed = mutableListOf<String>()
        listOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS
        ).forEach {
            if (ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED)
                needed.add(it)
        }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), SMS_PERMISSION_CODE)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        if (requestCode == SMS_PERMISSION_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Permiso SMS concedido ✅", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "⚠️ Sin permiso SMS la app no funcionará", Toast.LENGTH_LONG).show()
            }
        }
    }
}
