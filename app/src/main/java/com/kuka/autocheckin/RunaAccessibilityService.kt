package com.kuka.autocheckin

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telephony.SmsManager
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class RunaAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AutoCheckin_A11y"
        private const val RUNA_PACKAGE = "com.runa.mobile"
        private const val CHECKIN_VALID_MS = 5 * 60 * 1000L
        private const val DELAY_SHORT  = 700L
        private const val DELAY_MEDIUM = 1200L
        private const val DELAY_RETRY  = 1800L
        private const val MAX_RETRIES  = 3
    }

    private enum class FlowState {
        IDLE,
        LOGIN_FILLING, LOGIN_CONFIRM_COMPANY, LOGIN_REGISTERED,
        MAIN_SCREEN, ENTERING_CODE, CONFIRM_IDENTITY,
        CONFIRM_LOCATION, TAKING_PHOTO, DONE
    }

    private val handler = Handler(Looper.getMainLooper())
    private var currentState  = FlowState.IDLE
    private var employeeCode  = ""
    private var pendingAction = "ENTRADA"
    private var senderNumber  = ""
    private var codeDigitIndex = 0
    private var stateRetries   = 0

    // ─── Eventos ─────────────────────────────────────────────────────────────
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName?.toString() != RUNA_PACKAGE) return
        if (!hasPendingCheckin()) return

        val root = rootInActiveWindow ?: return
        val screenText = extractAllText(root)
        Log.d(TAG, "Estado=$currentState | Reintentos=$stateRetries | Screen: ${screenText.take(100)}")

        handler.removeCallbacksAndMessages(null)
        handler.postDelayed({ processScreen(screenText, root) }, DELAY_SHORT)
    }

    override fun onInterrupt() { resetState() }

    // ─── Máquina de estados ──────────────────────────────────────────────────
    private fun processScreen(text: String, root: AccessibilityNodeInfo) {
        when {

            // ── LOGIN: email y contraseña ──────────────────────────────────
            text.contains("Ingresa tu email", ignoreCase = true) &&
            text.contains("Contraseña", ignoreCase = true) -> {
                if (currentState == FlowState.IDLE || currentState == FlowState.LOGIN_FILLING) {
                    transitionTo(FlowState.LOGIN_FILLING)
                    fillLoginForm(root)
                }
            }

            // ── LOGIN: Confirmar empresa ───────────────────────────────────
            text.contains("Confirma tu empresa", ignoreCase = true) -> {
                if (expectState(FlowState.LOGIN_FILLING, FlowState.LOGIN_CONFIRM_COMPANY, text)) {
                    transitionTo(FlowState.LOGIN_CONFIRM_COMPANY)
                    handler.postDelayed({
                        selectCompanyAndConfirm(rootInActiveWindow ?: return@postDelayed)
                    }, DELAY_MEDIUM)
                }
            }

            // ── LOGIN: Felicitaciones ──────────────────────────────────────
            text.contains("Felicitaciones", ignoreCase = true) &&
            text.contains("registrado en Runa", ignoreCase = true) -> {
                if (expectState(FlowState.LOGIN_CONFIRM_COMPANY, FlowState.LOGIN_REGISTERED, text)) {
                    transitionTo(FlowState.LOGIN_REGISTERED)
                    clickWithRetry(root, "CONTINUAR", FlowState.MAIN_SCREEN, text)
                }
            }

            // ── PRINCIPAL: KUKA DE MÉXICO ──────────────────────────────────
            text.contains("KUKA DE MÉXICO", ignoreCase = true) &&
            (text.contains("ENTRADA", ignoreCase = true) ||
             text.contains("SALIDA", ignoreCase = true)) -> {
                val validPrev = currentState in listOf(
                    FlowState.IDLE, FlowState.MAIN_SCREEN, FlowState.LOGIN_REGISTERED
                )
                if (validPrev) {
                    transitionTo(FlowState.MAIN_SCREEN)
                    loadActionData()
                    handler.postDelayed({
                        val r = rootInActiveWindow ?: return@postDelayed
                        val clicked = clickButtonWithText(r, pendingAction)
                        if (!clicked) scheduleRetry(text, "Botón '$pendingAction' no encontrado")
                    }, DELAY_MEDIUM)
                }
            }

            // ── CÓDIGO ─────────────────────────────────────────────────────
            text.contains("Ingresa tu código", ignoreCase = true) -> {
                if (expectState(FlowState.MAIN_SCREEN, FlowState.ENTERING_CODE, text)) {
                    transitionTo(FlowState.ENTERING_CODE)
                    codeDigitIndex = 0
                    handler.postDelayed({
                        enterNextDigit(rootInActiveWindow ?: return@postDelayed)
                    }, DELAY_MEDIUM)
                }
            }

            // ── CONFIRMAR IDENTIDAD ────────────────────────────────────────
            text.contains("Confírmanos que eres tú", ignoreCase = true) -> {
                if (expectState(FlowState.ENTERING_CODE, FlowState.CONFIRM_IDENTITY, text)) {
                    transitionTo(FlowState.CONFIRM_IDENTITY)
                    clickWithRetry(root, "SI", FlowState.CONFIRM_LOCATION, text)
                }
            }

            // ── UBICACIÓN ─────────────────────────────────────────────────
            text.contains("No estás en la oficina", ignoreCase = true) ||
            text.contains("Confírmanos la dirección", ignoreCase = true) -> {
                if (expectState(FlowState.CONFIRM_IDENTITY, FlowState.CONFIRM_LOCATION, text)) {
                    transitionTo(FlowState.CONFIRM_LOCATION)
                    clickWithRetry(root, "SI", FlowState.TAKING_PHOTO, text)
                }
            }

            // ── FOTO ───────────────────────────────────────────────────────
            text.contains("Sonríe", ignoreCase = true) ||
            text.contains("tómate tu foto", ignoreCase = true) -> {
                if (expectState(FlowState.CONFIRM_LOCATION, FlowState.TAKING_PHOTO, text)) {
                    transitionTo(FlowState.TAKING_PHOTO)
                    clickWithRetry(root, "OK", FlowState.DONE, text)
                }
            }

            // ── LISTO ──────────────────────────────────────────────────────
            text.contains("Listo", ignoreCase = true) &&
            text.contains("registrado", ignoreCase = true) -> {
                if (expectState(FlowState.TAKING_PHOTO, FlowState.DONE, text)) {
                    transitionTo(FlowState.DONE)
                    handler.postDelayed({
                        val r = rootInActiveWindow ?: return@postDelayed
                        clickButtonWithText(r, "OK")
                        Log.i(TAG, "✅ $pendingAction completado. Código: $employeeCode")
                        sendConfirmationSms()
                        clearPendingCheckin()
                    }, DELAY_MEDIUM)
                }
            }
        }
    }

    // ─── Login ───────────────────────────────────────────────────────────────
    private fun fillLoginForm(root: AccessibilityNodeInfo) {
        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)

        // Prioridad: credenciales de sesión SMS > credenciales guardadas en app
        val useSession = prefs.getBoolean("use_session_credentials", false)
        val email = if (useSession)
            prefs.getString("session_email",    "") ?: ""
        else
            prefs.getString(MainActivity.KEY_EMAIL, "") ?: ""

        val password = if (useSession)
            prefs.getString("session_password", "") ?: ""
        else
            prefs.getString(MainActivity.KEY_PASSWORD, "") ?: ""

        if (email.isEmpty() || password.isEmpty()) {
            Log.e(TAG, "Sin credenciales disponibles (ni sesión SMS ni app).")
            // Pedir credenciales por SMS si tenemos el número del remitente
            if (senderNumber.isNotEmpty()) {
                prefs.edit()
                    .putString("waiting_credentials_sender", senderNumber)
                    .putLong("waiting_credentials_time", System.currentTimeMillis())
                    .apply()
                sendRawSms(senderNumber,
                    "🔐 AutoCheckin KUKA\nNecesito tus credenciales de Runa.\n" +
                    "Responde con:\nlogin correo@empresa.com tuContraseña")
            }
            resetState()
            return
        }

        fillTextFieldByIndex(root, 0, email)
        handler.postDelayed({
            val r = rootInActiveWindow ?: return@postDelayed
            fillTextFieldByIndex(r, 1, password)
            handler.postDelayed({
                val r2 = rootInActiveWindow ?: return@postDelayed
                val clicked = clickButtonWithText(r2, "CONTINUAR")
                if (!clicked) scheduleRetry("", "Botón CONTINUAR no encontrado en login")
                // Limpiar credenciales de sesión temporal tras usarlas
                if (useSession) {
                    prefs.edit()
                        .remove("session_email")
                        .remove("session_password")
                        .putBoolean("use_session_credentials", false)
                        .apply()
                    Log.d(TAG, "Credenciales de sesión temporal eliminadas")
                }
            }, DELAY_MEDIUM)
        }, DELAY_MEDIUM)
    }

    // ─── Verificación y reintentos ───────────────────────────────────────────
    private fun expectState(
        expectedPrev: FlowState, nextState: FlowState, screenText: String
    ): Boolean {
        return when (currentState) {
            expectedPrev -> true
            nextState -> {
                stateRetries++
                if (stateRetries > MAX_RETRIES) {
                    Log.e(TAG, "❌ Máx reintentos en $currentState. Abortando.")
                    clearPendingCheckin()
                    false
                } else {
                    Log.w(TAG, "⚠️ Reintento $stateRetries/$MAX_RETRIES en $currentState")
                    true
                }
            }
            else -> false
        }
    }

    private fun clickWithRetry(
        root: AccessibilityNodeInfo, buttonText: String,
        nextExpectedState: FlowState, currentScreenText: String
    ) {
        handler.postDelayed({
            val r = rootInActiveWindow ?: run {
                scheduleRetry(currentScreenText, "rootInActiveWindow null al tocar '$buttonText'")
                return@postDelayed
            }
            val clicked = clickButtonWithText(r, buttonText)
            if (!clicked) scheduleRetry(currentScreenText, "Botón '$buttonText' no encontrado")
            else Log.d(TAG, "✔ Botón '$buttonText' presionado")
        }, DELAY_MEDIUM)
    }

    private fun scheduleRetry(lastScreenText: String, reason: String) {
        stateRetries++
        if (stateRetries > MAX_RETRIES) {
            Log.e(TAG, "❌ Abortando en $currentState. Razón: $reason")
            clearPendingCheckin()
            return
        }
        Log.w(TAG, "⚠️ Reintento $stateRetries/$MAX_RETRIES | Estado=$currentState | $reason")
        handler.postDelayed({
            val root = rootInActiveWindow ?: return@postDelayed
            processScreen(extractAllText(root), root)
        }, DELAY_RETRY)
    }

    private fun transitionTo(newState: FlowState) {
        if (currentState != newState) {
            Log.i(TAG, "→ $currentState → $newState")
            currentState = newState
            stateRetries = 0
        }
    }

    // ─── Empresa ──────────────────────────────────────────────────────────────
    private fun selectCompanyAndConfirm(root: AccessibilityNodeInfo) {
        val radio = collectAllNodes(root).firstOrNull {
            it.className?.toString()?.contains("RadioButton") == true && !it.isChecked
        }
        radio?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        handler.postDelayed({
            val r = rootInActiveWindow ?: return@postDelayed
            val clicked = clickButtonWithText(r, "CONFIRMAR")
            if (!clicked) scheduleRetry("", "Botón CONFIRMAR no encontrado")
        }, DELAY_MEDIUM)
    }

    // ─── Código numérico ──────────────────────────────────────────────────────
    private fun enterNextDigit(root: AccessibilityNodeInfo) {
        if (codeDigitIndex >= employeeCode.length) {
            handler.postDelayed({
                val r = rootInActiveWindow ?: return@postDelayed
                val clicked = clickButtonWithText(r, "CONTINUAR")
                if (!clicked) scheduleRetry("", "Botón CONTINUAR no encontrado tras código")
            }, DELAY_SHORT)
            return
        }
        val digit = employeeCode[codeDigitIndex].toString()
        val digitNode = findClickableNodeByText(root, digit)
        if (digitNode != null) {
            digitNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            codeDigitIndex++
            handler.postDelayed({
                val r = rootInActiveWindow ?: return@postDelayed
                enterNextDigit(r)
            }, 450L)
        } else {
            handler.postDelayed({
                val r = rootInActiveWindow ?: return@postDelayed
                enterNextDigit(r)
            }, DELAY_RETRY)
        }
    }

    // ─── SMS de confirmación ──────────────────────────────────────────────────
    private fun sendConfirmationSms() {
        if (senderNumber.isEmpty()) return
        try {
            val now = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
                .format(java.util.Date())
            val accion = if (pendingAction == "ENTRADA") "entrada" else "salida"
            val msg = "✅ Registro de $accion completado para código $employeeCode a las $now"
            sendRawSms(senderNumber, msg)
        } catch (e: Exception) {
            Log.e(TAG, "Error enviando SMS de confirmación: ${e.message}")
        }
    }

    private fun sendRawSms(number: String, message: String) {
        if (number.isEmpty()) return
        try {
            val smsManager = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                applicationContext.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            smsManager.sendTextMessage(number, null, message, null, null)
            Log.i(TAG, "SMS enviado a $number")
        } catch (e: Exception) {
            Log.e(TAG, "Error SMS: ${e.message}")
        }
    }

    // ─── Helpers de nodos ────────────────────────────────────────────────────
    private fun clickButtonWithText(root: AccessibilityNodeInfo, text: String): Boolean {
        val allNodes = collectAllNodes(root)
        val direct = allNodes.firstOrNull { node ->
            node.isClickable &&
            (node.text?.toString()?.trim().equals(text, ignoreCase = true) ||
             node.contentDescription?.toString()?.trim().equals(text, ignoreCase = true))
        }
        if (direct != null) { direct.performAction(AccessibilityNodeInfo.ACTION_CLICK); return true }

        val textNode = allNodes.firstOrNull { it.text?.toString()?.trim().equals(text, ignoreCase = true) }
        if (textNode != null) {
            var parent = textNode.parent; var depth = 0
            while (parent != null && depth < 5) {
                if (parent.isClickable) { parent.performAction(AccessibilityNodeInfo.ACTION_CLICK); return true }
                parent = parent.parent; depth++
            }
        }
        Log.w(TAG, "Botón no encontrado: '$text'")
        return false
    }

    private fun findClickableNodeByText(root: AccessibilityNodeInfo, text: String) =
        collectAllNodes(root).firstOrNull { it.isClickable && it.text?.toString()?.trim() == text }

    private fun fillTextField(node: AccessibilityNodeInfo, text: String) {
        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun fillTextFieldByIndex(root: AccessibilityNodeInfo, index: Int, value: String) {
        val editTexts = collectAllNodes(root).filter {
            it.className?.toString()?.contains("EditText") == true
        }
        if (index < editTexts.size) fillTextField(editTexts[index], value)
        else Log.w(TAG, "EditText[$index] no encontrado")
    }

    private fun collectAllNodes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val list = mutableListOf<AccessibilityNodeInfo>()
        fun traverse(node: AccessibilityNodeInfo) {
            list.add(node)
            for (i in 0 until node.childCount) { val c = node.getChild(i) ?: continue; traverse(c) }
        }
        traverse(root); return list
    }

    private fun extractAllText(root: AccessibilityNodeInfo) =
        collectAllNodes(root).joinToString(" ") {
            buildString {
                it.text?.let { t -> append(t).append(" ") }
                it.contentDescription?.let { d -> append(d).append(" ") }
            }
        }

    // ─── Estado pendiente ────────────────────────────────────────────────────
    private fun hasPendingCheckin(): Boolean {
        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        val code  = prefs.getString("pending_checkin_code", "") ?: ""
        val time  = prefs.getLong("pending_checkin_time", 0L)
        val valid = code.isNotEmpty() && (System.currentTimeMillis() - time) < CHECKIN_VALID_MS
        if (!valid && currentState != FlowState.IDLE) resetState()
        return valid
    }

    private fun loadActionData() {
        val prefs = getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
        employeeCode  = prefs.getString("pending_checkin_code",   "") ?: ""
        pendingAction = prefs.getString("pending_checkin_action", "ENTRADA") ?: "ENTRADA"
        senderNumber  = prefs.getString("pending_checkin_sender", "") ?: ""
        Log.i(TAG, "Acción: $pendingAction | Código: $employeeCode | Remitente: $senderNumber")
    }

    private fun clearPendingCheckin() {
        getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove("pending_checkin_code")
            .remove("pending_checkin_action")
            .remove("pending_checkin_sender")
            .remove("pending_checkin_time")
            .remove("session_email")
            .remove("session_password")
            .putBoolean("use_session_credentials", false)
            .apply()
        resetState()
    }

    private fun resetState() {
        currentState   = FlowState.IDLE
        employeeCode   = ""
        pendingAction  = "ENTRADA"
        senderNumber   = ""
        codeDigitIndex = 0
        stateRetries   = 0
        handler.removeCallbacksAndMessages(null)
    }
}
