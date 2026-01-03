package com.aurivox.onlineassistant

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import kotlin.math.roundToInt
import kotlin.random.Random

data class ParsedIntent(val action: String, val params: Map<String, String> = emptyMap())
data class CommandResult(val spoken: String)

object IntentParser {
    // … parser code unchanged …
}

object CommandExecutor {

    suspend fun execute(context: Context, intent: ParsedIntent): CommandResult {
        return when (intent.action) {
            "SMALLTALK_HOW" -> CommandResult("I’m feeling clear and focused. How’s your mood right now?")
            "USER_MOOD" -> CommandResult("Mood noted: ${intent.params["mood"]}")

            "OPEN_APP" -> openApp(context, intent.params["app"] ?: "")
            "YOUTUBE_SEARCH" -> openYouTubeSearch(context, intent.params["query"] ?: "")
            "WHATSAPP_CHAT" -> openWhatsAppChat(context, intent.params["name"] ?: "")

            "WIFI_PANEL" -> openPanel(context, Settings.Panel.ACTION_WIFI, "Wi‑Fi panel.")
            "BT_SETTINGS" -> openSettings(context, Settings.ACTION_BLUETOOTH_SETTINGS, "Bluetooth settings.")
            "AIRPLANE_SETTINGS" -> openSettings(context, Settings.ACTION_AIRPLANE_MODE_SETTINGS, "Airplane mode settings.")
            "BATTERY_SAVER_SETTINGS" -> openSettings(context, Settings.ACTION_BATTERY_SAVER_SETTINGS, "Battery saver settings.")
            "FLASHLIGHT_ON" -> setTorch(context, true)
            "FLASHLIGHT_OFF" -> setTorch(context, false)
            "FLASHLIGHT_TOGGLE" -> toggleTorch(context)
            "DISPLAY_SETTINGS" -> openSettings(context, Settings.ACTION_DISPLAY_SETTINGS, "Display settings.")
            "VOLUME_SET" -> adjustVolume(context, intent.params["delta"] ?: "set")
            "NOTIFICATION_SETTINGS" -> openAppNotificationSettings(context)   // ✅ FIXED
            "SCREENSHOT_HINT" -> CommandResult("Use Power + Volume Down to take a screenshot.")
            "LOCK_HINT" -> openSettings(context, Settings.ACTION_SECURITY_SETTINGS, "Security settings.")

            else -> CommandResult("Done.")
        }
    }

    private fun openAppNotificationSettings(context: Context): CommandResult {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra("android.provider.extra.APP_PACKAGE", context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return CommandResult("Notification settings.")
    }

    private fun openPanel(context: Context, action: String, spoken: String): CommandResult {
        context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return CommandResult(spoken)
    }

    private fun openSettings(context: Context, action: String, spoken: String): CommandResult {
        context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return CommandResult(spoken)
    }

    private fun setTorch(context: Context, on: Boolean): CommandResult {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val camId = cm.cameraIdList.firstOrNull()
        return try {
            if (camId != null) cm.setTorchMode(camId, on)
            CommandResult(if (on) "Flashlight on." else "Flashlight off.")
        } catch (_: Exception) {
            CommandResult("Flashlight control not available.")
        }
    }

    private fun toggleTorch(context: Context): CommandResult {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val camId = cm.cameraIdList.firstOrNull() ?: return CommandResult("Flashlight not available.")
        return try {
            cm.setTorchMode(camId, true)
            CommandResult("Flashlight toggled.")
        } catch (_: Exception) {
            CommandResult("Flashlight control not available.")
        }
    }

    private fun adjustVolume(context: Context, delta: String): CommandResult {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        when (delta) {
            "up" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
            "down" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
        }
        return CommandResult("Volume $delta.")
    }

    private fun openApp(context: Context, appName: String): CommandResult {
        val pm = context.packageManager
        val apps = pm.getInstalledApplications(0)
        val match = apps.firstOrNull { it.loadLabel(pm).toString().equals(appName, true) }
        match?.let {
            pm.getLaunchIntentForPackage(it.packageName)?.let { launch ->
                context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return CommandResult("Opening $appName.")
            }
        }
        return CommandResult("App not found.")
    }

    private fun openYouTubeSearch(context: Context, q: String): CommandResult {
        val uri = if (q.isNotBlank()) {
            Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q))
        } else {
            Uri.parse("https://www.youtube.com")
        }
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return CommandResult("YouTube opened.")
    }

    private fun openWhatsAppChat(context: Context, name: String): CommandResult {
        val num = findPhoneByName(context, name)
        return if (num != null) {
            val uri = Uri.parse("https://wa.me/$num")
            val i = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            i.`package` = "com.whatsapp"
            context.startActivity(i)
            CommandResult("WhatsApp chat with $name.")
        } else CommandResult("Contact not found.")
    }

    private fun findPhoneByName(context: Context, name: String): String? {
        val cr = context.contentResolver
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val proj = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val c = cr.query(uri, proj, null, null, null) ?: return null
        c.use {
            while (it.moveToNext()) {
                val n = it.getString(0) ?: ""
                val num = it.getString(1) ?: ""
                if (n.equals(name, true)) return num.replace("[^+\\d]".toRegex(), "")
            }
        }
        return null
    }

    private fun batteryStatus(context: Context): CommandResult {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        return CommandResult("Battery level is $level%.")
    }

    private fun storageStatus(): CommandResult {
        val stat = StatFs(Environment.getDataDirectory().path)
        val gb = 1024.0 * 1024 * 1024
        val avail = stat.availableBytes / gb
        val total = stat.totalBytes / gb
        return CommandResult("Storage: ${avail.roundToInt()} GB free of ${total.roundToInt()} GB.")
    }
}
