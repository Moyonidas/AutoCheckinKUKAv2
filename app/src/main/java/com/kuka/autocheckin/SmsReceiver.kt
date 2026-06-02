package com.kuka.autocheckin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log

class SmsReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "AutoCheckin_SMS"
        private const val RUNA_PACKAGE = "com.runa.mobile"

        private val CHECKIN_PATTERN  = Regex("""(?i)\bcheckin\s+(\d{4})\b""")
        private val CHECKOUT_PATTERN = Regex("""(?i)\bcheckout\s+(\d{4})\b""")
        // Formato: login usuario@email.com miContraseña123
        private val LOGIN_PATTERN    = Regex("""(?i)\blogin\s+(\S+@\S+)\s+(\S+)""")

        // Tiempo máximo para esperar las credenciales por SMS (10 minutos)
        private const val CREDENTIALS_WAIT_MS = 10 * 60 * 1000L
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages     = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        val fullBody     = messages.joinToString("") { it.messageBody }
        val senderNumber = messages.firstOrNull()?.originatingAddress ?: ""

        Log.d(TAG, "SMS de: $senderNumber | Cuerpo: $fullBody")

        val prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)

        // ── ¿Estamos esperando credenciales de este remitente? ──────────────
        val waitingSender = prefs.getString("waiting_credentials_sender", "") ?: ""
        val waitingTime   = prefs.getLong("waiting_credentials_time", 0L)
        val stillWaiting  = waitingSender == senderNumber &&
                            (System.currentTimeMillis() - waitingTime) < CREDENTIALS_WAIT_MS

        if (stillWaiting) {
            val loginMatch = LOGIN_PATTERN.find(fullBody)
            if (loginMatch != null) {
                val email    = loginMatch.groupValues[1]
                val password = loginMatch.groupValues[2]
                Log.d(TAG, "Credenciales recibidas por SMS para: $email")

                // Guardar temporalmente SOLO en memoria de la sesión pendiente
                prefs.edit()
                    .putString("session_email",    email)
                    .putString("session_password", password)
                    .putBoolean("use_session_credentials", true)
                    .remove("waiting_credentials_sender")
                    .remove("waiting_credentials_time")
                    .apply()

                sendSms(context, senderNumber, "🔐 Credenciales recibidas. Iniciando registro...")
                launchRuna(context)
                return
            } else {
                // Respuesta inválida mientras esperaba credenciales
                sendSms(context, senderNumber,
                    "⚠️ Formato incorrecto. Envía:\nlogin correo@empresa.com tuContraseña")
                return
            }
        }

        // ── Procesar checkin / checkout ─────────────────────────────────────
        val checkinMatch  = CHECKIN_PATTERN.find(fullBody)
        val checkoutMatch = CHECKOUT_PATTERN.find(fullBody)

        when {
            checkinMatch != null -> {
                handleAction(context, prefs, checkinMatch.groupValues[1], "ENTRADA", senderNumber)
            }
            checkoutMatch != null -> {
                handleAction(context, prefs, checkoutMatch.groupValues[1], "SALIDA", senderNumber)
            }
            else -> Log.d(TAG, "SMS sin patrón válido")
        }
    }

    private fun handleAction(
        context: Context,
        prefs: android.content.SharedPreferences,
        code: String,
        action: String,
        sender: String
    ) {
        Log.d(TAG, "$action detectado. Código: $code | Remitente: $sender")

        // Guardar acción pendiente
        prefs.edit()
            .putString("pending_checkin_code",   code)
            .putString("pending_checkin_action",  action)
            .putString("pending_checkin_sender",  sender)
            .putLong("pending_checkin_time",      System.currentTimeMillis())
            .putBoolean("use_session_credentials", false)
            .remove("session_email")
            .remove("session_password")
            .apply()

        // ¿Hay credenciales guardadas en la app?
        val savedEmail    = prefs.getString(MainActivity.KEY_EMAIL, "") ?: ""
        val savedPassword = prefs.getString(MainActivity.KEY_PASSWORD, "") ?: ""

        if (savedEmail.isNotEmpty() && savedPassword.isNotEmpty()) {
            // Tiene credenciales configuradas → lanzar directo
            Log.d(TAG, "Credenciales disponibles en app. Lanzando Runa.")
            launchRuna(context)
        } else {
            // No tiene credenciales → pedir por SMS
            Log.d(TAG, "Sin credenciales. Solicitando por SMS a $sender")
            prefs.edit()
                .putString("waiting_credentials_sender", sender)
                .putLong("waiting_credentials_time",     System.currentTimeMillis())
                .apply()

            sendSms(context, sender,
                "🔐 AutoCheckin KUKA\nNecesito tus credenciales de Runa.\n" +
                "Responde con:\nlogin correo@empresa.com tuContraseña")
        }
    }

    private fun launchRuna(context: Context) {
        val launchIntent = context.packageManager.getLaunchIntentForPackage(RUNA_PACKAGE)
        if (launchIntent != null) {
            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            context.startActivity(launchIntent)
            Log.d(TAG, "App Runa lanzada")
        } else {
            Log.e(TAG, "App Runa no encontrada. Package: $RUNA_PACKAGE")
        }
    }

    fun sendSms(context: Context, number: String, message: String) {
        if (number.isEmpty()) return
        try {
            val smsManager = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                context.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            smsManager.sendTextMessage(number, null, message, null, null)
            Log.i(TAG, "SMS enviado a $number: $message")
        } catch (e: Exception) {
            Log.e(TAG, "Error enviando SMS: ${e.message}")
        }
    }
}
