# AutoCheckin KUKA

App Android que automatiza el registro de entrada en Runa al recibir un SMS.

## Cómo funciona

1. Recibes un SMS con el formato: `checkin 0756`
2. La app detecta el mensaje y extrae el código de 4 dígitos
3. Lanza la app Runa automáticamente
4. Navega por todas las pantallas sin intervención:
   - Si hay sesión activa: ENTRADA → código → confirmar identidad → ubicación → foto → ¡Listo!
   - Si no hay sesión: Login → confirmar empresa → ENTRADA → código → ...

---

## Requisitos previos

- Android Studio Hedgehog o superior
- Android SDK 34
- Kotlin 1.9+
- Dispositivo con Android 8.0+ (API 26)
- App **Runa** instalada

---

## Compilar e instalar

```bash
# Desde la raíz del proyecto:
./gradlew assembleDebug

# Instalar en dispositivo conectado:
./gradlew installDebug
```

O abrir en Android Studio → Build → Run.

---

## Configuración inicial (OBLIGATORIA)

Al abrir la app por primera vez:

### 1. Activar Servicio de Accesibilidad
- Toca **"Activar Servicio de Accesibilidad"**
- Ve a: AutoCheckin KUKA → activa el toggle
- Regresa a la app

### 2. Guardar credenciales Runa
- Ingresa el **email** y **contraseña** de Runa del empleado
- Toca **GUARDAR CREDENCIALES**

### 3. Verificar permisos SMS
- Al primer arranque solicita permiso automáticamente
- Si lo rechazaste: Ajustes del sistema → Apps → AutoCheckin KUKA → Permisos → SMS

---

## Formato del SMS disparador

```
checkin XXXX
```
- `checkin` puede ser mayúscula o minúscula
- `XXXX` = código de 4 dígitos del empleado (ej: 0756)
- Puede haber texto adicional antes o después

**Ejemplos válidos:**
- `checkin 0756`
- `CHECKIN 1234`
- `Por favor: checkin 0756 gracias`

---

## Archivos principales

| Archivo | Función |
|---|---|
| `SmsReceiver.kt` | Detecta el SMS y extrae el código |
| `RunaAccessibilityService.kt` | Navega automáticamente en Runa |
| `MainActivity.kt` | Configuración de credenciales y permisos |
| `accessibility_service_config.xml` | Config del servicio de accesibilidad |
| `AndroidManifest.xml` | Permisos y registro de componentes |

---

## Notas importantes

- El checkin pendiente expira en **5 minutos** si Runa no completa el flujo
- Las credenciales se guardan en **SharedPreferences** cifradas del sistema
- El package de Runa configurado es: `com.runa.mobile`
  → Si es diferente, actualizar en `SmsReceiver.kt` y `accessibility_service_config.xml`
- En Android 12+, puede ser necesario permitir el inicio en segundo plano desde Ajustes

---

## Troubleshooting

**La app no responde al SMS:**
- Verificar que el permiso RECEIVE_SMS esté concedido
- En algunos fabricantes (Xiaomi, Samsung, Huawei) hay que agregar la app a la "lista blanca" de batería

**El Accessibility Service se desactiva solo:**
- Algunos fabricantes desactivan servicios de accesibilidad para ahorrar batería
- Ir a Ajustes → Batería → Optimización → excluir AutoCheckin KUKA

**Runa abre pero no navega:**
- Verificar que el Servicio de Accesibilidad esté ACTIVO (indicador verde en la app)
- Puede ser que el package name de Runa sea diferente; verificar con `adb shell pm list packages | grep runa`
