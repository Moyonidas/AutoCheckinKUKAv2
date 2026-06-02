package com.kuka.autocheckin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.d("AutoCheckin_Boot", "Dispositivo reiniciado. El receptor SMS está activo.")
            // El BroadcastReceiver de SMS se registra automáticamente vía Manifest
            // No se requiere acción adicional
        }
    }
}
