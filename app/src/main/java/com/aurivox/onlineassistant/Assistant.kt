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

    private val verbsOpen = setOf("open", "start", "launch", "run")
    private val verbsSearch = setOf("search", "find", "lookup", "look", "look up")
    private val verbsCall = setOf("call", "dial", "phone")
    private val verbsMessage = setOf("message", "text", "sms")
    private val verbsEmail = setOf("email", "mail", "gmail")
    private val wordsOn = setOf("on", "enable")
    private val wordsOff = setOf("off", "disable")

    private val appKeywords = mapOf(
        "whatsapp" to "WhatsApp",
        "youtube" to "YouTube",
        "gmail" to "Gmail",
        "chrome" to "Chrome",
        "maps" to "Maps",
        "camera" to "Camera",
        "clock" to "Clock",
        "calendar" to "Calendar",
        "messages" to "Messages"
    )

    private val moodWords = mapOf(
        "happy" to "Positive",
        "good" to "Positive",
        "great" to "Positive",
        "excited" to "Positive",
        "calm" to "Positive",
        "ok" to "Neutral",
        "fine" to "Neutral",
        "meh" to "Neutral",
        "tired" to "Tired",
        "stressed" to "Stressed",
        "anxious" to "Stressed",
        "sad" to "Low",
        "down" to "Low",
        "angry" to "Low",
        "frustrated" to "Low"
    )

    fun parse(textRaw: String): ParsedIntent {
        val t = textRaw.lowercase().trim()
        val tokens = tokenize(t)

        if (t.contains("how are you")) return ParsedIntent("SMALLTALK_HOW", emptyMap())

        if (tokens.any { it in moodWords.keys } || t.contains("i am ") || t.contains("i'm ") || t.contains("feeling ")) {
            val moodKey = moodWords.keys.firstOrNull { t.contains(it) } ?: ""
            return ParsedIntent("USER_MOOD", mapOf("mood" to (moodWords[moodKey] ?: "Unknown"), "raw" to t))
        }

        appKeywords.keys.firstOrNull { it in tokens }?.let { key ->
            if (verbsOpen.any { it in tokens } || tokens.size == 1) {
                return ParsedIntent("OPEN_APP", mapOf("app" to appKeywords[key]!!))
            }
            if (("youtube" == key) && verbsSearch.any { it in tokens }) {
                val q = t.replace("youtube", "").replace("search", "").trim()
                return ParsedIntent("YOUTUBE_SEARCH", mapOf("query" to q))
            }
        }

        if ("whatsapp" in tokens && (tokens.contains("chat") || tokens.contains("message"))) {
            val name = extractName(t, listOf("chat", "message", "on whatsapp", "whatsapp")) ?: ""
            return ParsedIntent("WHATSAPP_CHAT", mapOf("name" to name))
        }

        if ("wifi" in tokens) return ParsedIntent("WIFI_PANEL")
        if ("bluetooth" in tokens) return ParsedIntent("BT_SETTINGS")
        if ("airplane" in tokens) return ParsedIntent("AIRPLANE_SETTINGS")
        if (tokens.any { it in setOf("power", "battery") } && tokens.contains("saver")) return ParsedIntent("BATTERY_SAVER_SETTINGS")
        if (tokens.any { it in setOf("flashlight", "torch") }) {
            val on = tokens.any { it in wordsOn }
            val off = tokens.any { it in wordsOff }
            return ParsedIntent(if (on) "FLASHLIGHT_ON" else if (off) "FLASHLIGHT_OFF" else "FLASHLIGHT_TOGGLE")
        }
        if ("brightness" in tokens) return ParsedIntent("DISPLAY_SETTINGS")
        if ("volume" in tokens) {
            val delta = when {
                tokens.contains("up") || tokens.contains("increase") -> "up"
                tokens.contains("down") || tokens.contains("decrease") -> "down"
                else -> "set"
            }
            return ParsedIntent("VOLUME_SET", mapOf("delta" to delta))
        }
        if (tokens.contains("notifications")) return ParsedIntent("NOTIFICATION_SETTINGS")
        if (tokens.contains("screenshot")) return ParsedIntent("SCREENSHOT_HINT")
        if (tokens.any { it in setOf("lock", "lockscreen") }) return ParsedIntent("LOCK_HINT")

        verbsCall.firstOrNull { it in tokens }?.let {
            val name = extractAfterWord(t, it) ?: t
            return ParsedIntent("CALL_CONTACT", mapOf("name" to name.trim()))
        }
        if (verbsMessage.any { it in tokens }) return ParsedIntent("SMS_COMPOSE", mapOf("to" to extractRecipient(t)))
        if (verbsEmail.any { it in tokens } || tokens.contains("email")) {
            val q = t.replace("email", "").replace("gmail", "").trim()
            return ParsedIntent("EMAIL_COMPOSE", mapOf("query" to q))
        }
        if (tokens.contains("redial")) return ParsedIntent("REDIAL_HINT")
        if (tokens.contains("read") && tokens.contains("messages")) return ParsedIntent("READ_MESSAGES_HINT")

        if (tokens.contains("alarm") && tokens.contains("set")) return ParsedIntent("SET_ALARM", mapOf("time" to extractTime(t)))
        if (tokens.contains("alarm") && tokens.contains("cancel")) return ParsedIntent("CANCEL_ALARM_HINT")
        if (tokens.contains("alarm") && tokens.contains("snooze")) return ParsedIntent("SNOOZE_ALARM_HINT")
        if (tokens.contains("alarms") && tokens.contains("list")) return ParsedIntent("LIST_ALARMS_HINT")
        if (tokens.contains("remind")) return ParsedIntent("SET_REMINDER", mapOf("message" to (extractAfterWord(t, "remind") ?: ""), "time" to extractTime(t)))
        if (tokens.contains("calendar")) return ParsedIntent("OPEN_CALENDAR")
        if (tokens.firstOrNull() == "note" || tokens.contains("note")) {
            val txt = t.substringAfter("note", "").trim()
            if (txt.isNotEmpty()) return ParsedIntent("ADD_NOTE", mapOf("text" to txt))
        }
        if (tokens.contains("read") && tokens.contains("notes")) return ParsedIntent("READ_NOTES")
        if (tokens.contains("delete") && tokens.contains("notes")) return ParsedIntent("DELETE_NOTES")

        if (tokens.contains("time")) return ParsedIntent("TELL_TIME")
        if (tokens.contains("date")) return ParsedIntent("TELL_DATE")
        if (tokens.contains("battery")) return ParsedIntent("BATTERY_STATUS")
        if (tokens.contains("storage")) return ParsedIntent("STORAGE_STATUS")

        if (tokens.contains("play")) return ParsedIntent("MEDIA_PLAY")
        if (tokens.contains("pause") || tokens.contains("stop")) return ParsedIntent("MEDIA_PAUSE")
        if (tokens.contains("next") || tokens.contains("skip")) return ParsedIntent("MEDIA_NEXT")
        if (tokens.contains("previous") || tokens.contains("prev") || tokens.contains("back")) return ParsedIntent("MEDIA_PREV")
        if (tokens.contains("mute")) return ParsedIntent("MEDIA_MUTE")
        if (tokens.contains("unmute")) return ParsedIntent("MEDIA_UNMUTE")

        if (t.startsWith("calculate ") || t.startsWith("calc ")) return ParsedIntent("CALCULATE", mapOf("expr" to t.substringAfter(" ").trim()))
        if (tokens.contains("convert")) return ParsedIntent("CONVERT", mapOf("raw" to t))
        if (tokens.contains("define")) return ParsedIntent("DEFINE", mapOf("word" to t.substringAfter("define ").trim()))
        if (tokens.contains("spell")) return ParsedIntent("SPELL", mapOf("word" to t.substringAfter("spell ").trim()))
        if (tokens.contains("random") && tokens.contains("number")) return ParsedIntent("RANDOM_NUMBER")
        if (tokens.contains("coin")) return ParsedIntent("COIN_FLIP")
        if (tokens.contains("joke")) return ParsedIntent("TELL_JOKE")
        if (tokens.contains("quote")) return ParsedIntent("TELL_QUOTE")
        if (tokens.contains("tech") && tokens.contains("fact")) return ParsedIntent("TECH_FACT")
        if (tokens.contains("motivation") || tokens.contains("motivational")) return ParsedIntent("MOTIVATION")

        if (tokens.any { it in setOf("research", "explain", "compare", "summarize", "search") }) {
            val q = t.substringAfter(tokens.find { it in setOf("research","explain","compare","summarize","search") } ?: "").trim()
            return ParsedIntent("RESEARCH", mapOf("query" to q))
        }

        return ParsedIntent("UNKNOWN", mapOf("raw" to t))
    }

    private fun tokenize(t: String): Set<String> =
        t.split(Regex("\\s+")).map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    private fun extractAfterWord(t: String, word: String): String? {
        val idx = t.indexOf(word)
        return if (idx >= 0) t.substring(idx + word.length).trim() else null
    }

    private fun extractName(t: String, anchors: List<String>): String? {
        var res = t
        anchors.forEach { a -> res = res.replace(a, " ") }
        return res.replace("whatsapp", " ").trim().takeIf { it.isNotEmpty() }
    }

    private fun extractRecipient(t: String): String =
        t.replace("message", "").replace("text", "").replace("sms", "").trim()

    private fun extractTime(text: String): String {
        Regex("""at (\d{1,2}:\d{2})""").find(text)?.let { return it.groupValues[1] }
        Regex("""in (\d{1,2}) (minute|minutes|hour|hours)""").find(text)?.let { return it.value }
        return "soon"
    }
}

object CommandExecutor {

    // suspend to allow calling Utils.answerShortOnline (suspend)
    suspend fun execute(context: Context, intent: ParsedIntent): CommandResult {
        return when (intent.action) {
            "SMALLTALK_HOW" -> CommandResult("I’m feeling clear and focused. How’s your mood right now?")
            "USER_MOOD" -> respondToMood(intent.params["mood"] ?: "Unknown", intent.params["raw"] ?: "")

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
            "NOTIFICATION_SETTINGS" -> openAppNotificationSettings(context) // fixed
            "SCREENSHOT_HINT" -> CommandResult("Use Power + Volume Down to take a screenshot.")
            "LOCK_HINT" -> openSettings(context, Settings.ACTION_SECURITY_SETTINGS, "Security settings.")

            "CALL_CONTACT" -> callContact(context, intent.params["name"] ?: "")
            "REDIAL_HINT" -> CommandResult("Open dialer to redial.")
            "SMS_COMPOSE" -> smsCompose(context, intent.params["to"] ?: "")
            "EMAIL_COMPOSE" -> emailCompose(context)

            "SET_ALARM" -> setAlarm(context)
            "CANCEL_ALARM_HINT" -> CommandResult("Manage alarms in Clock app.")
            "SNOOZE_ALARM_HINT" -> CommandResult("Snooze from alarm notification.")
            "LIST_ALARMS_HINT" -> CommandResult("View alarms in Clock.")
            "SET_REMINDER" -> reminder(context, intent.params["message"] ?: "", intent.params["time"] ?: "soon")
            "OPEN_CALENDAR" -> openCalendar(context)
            "ADD_NOTE" -> { Notes.save(context, intent.params["text"] ?: ""); CommandResult("Note added.") }
            "READ_NOTES" -> CommandResult(Notes.get(context).ifBlank { "No notes yet." })
            "DELETE_NOTES" -> { Notes.clear(context); CommandResult("Notes deleted.") }

            "TELL_TIME" -> CommandResult("It’s ${java.time.LocalTime.now().withNano(0)}.")
            "TELL_DATE" -> CommandResult("Today is ${java.time.LocalDate.now()}.")
            "BATTERY_STATUS" -> batteryStatus(context)
            "STORAGE_STATUS" -> storageStatus()

            "MEDIA_PLAY" -> CommandResult("Play.")
            "MEDIA_PAUSE" -> CommandResult("Pause.")
            "MEDIA_NEXT" -> CommandResult("Next track.")
            "MEDIA_PREV" -> CommandResult("Previous track.")
            "MEDIA_MUTE" -> { adjustMute(context, true); CommandResult("Muted.") }
            "MEDIA_UNMUTE" -> { adjustMute(context, false); CommandResult("Unmuted.") }

            "CALCULATE" -> calcLocal(intent.params["expr"] ?: "")
            "CONVERT" -> CommandResult(Utils.answerShortOnline("Convert: ${intent.params["raw"] ?: ""}"))
            "DEFINE" -> CommandResult(Utils.answerShortOnline("Define: ${intent.params["word"] ?: ""}"))
            "SPELL" -> spell(intent.params["word"] ?: "")
            "RANDOM_NUMBER" -> CommandResult("Random number: ${Random.nextInt(1, 101)}.")
            "COIN_FLIP" -> CommandResult("Coin flip: " + listOf("Heads", "Tails").random())
            "TELL_JOKE" -> CommandResult(Utils.answerShortOnline("Tell a short clean tech joke."))
            "TELL_QUOTE" -> CommandResult(Utils.answerShortOnline("Give one short motivational quote."))
            "TECH_FACT" -> CommandResult(Utils.answerShortOnline("Give one short interesting tech fact."))
            "MOTIVATION" -> CommandResult(Utils.answerShortOnline("Give one short motivational line."))

            "RESEARCH" -> CommandResult(Utils.answerShortOnline("Short helpful answer:\n${intent.params["query"] ?: ""}"))
            "UNKNOWN" -> CommandResult(Utils.answerShortOnline("Understand and help: ${intent.params["raw"] ?: ""}"))

            else -> CommandResult("Done.")
        }
    }

    private fun openAppNotificationSettings(context: Context): CommandResult {
        val i = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra("android.provider.extra.APP_PACKAGE", context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
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
    private fun openCalendar(context: Context): CommandResult {
        val i = Intent(Intent.ACTION_VIEW)
            .setData(Uri.parse("content://com.android.calendar/time/"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
        return CommandResult("Calendar.")
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
            CommandResult("Flashlight on.")
        } catch (_: Exception) {
            CommandResult("Flashlight control not available.")
        }
    }

    private fun adjustVolume(context: Context, delta: String): CommandResult {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        when (delta) {
            "up" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
            "down" -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
            else -> {}
        }
        return CommandResult("Volume $delta.")
    }
    private fun adjustMute(context: Context, mute: Boolean) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.adjustStreamVolume(
            AudioManager.STREAM_MUSIC,
            if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
            0
        )
    }

    private fun callContact(context: Context, nameOrNumber: String): CommandResult {
        val digits = nameOrNumber.filter { it.isDigit() || it == '+' }
        return if (digits.length >= 5) {
            context.startActivity(
                Intent(Intent.ACTION_CALL, Uri.parse("tel:$digits"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            CommandResult("Calling.")
        } else {
            context.startActivity(Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            CommandResult("Dialer opened.")
        }
    }
    private fun smsCompose(context: Context, to: String): CommandResult {
        val uri = Uri.parse("smsto:$to")
        val i = Intent(Intent.ACTION_SENDTO, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
        return CommandResult("Messages opened.")
    }
    private fun emailCompose(context: Context): CommandResult {
        val intent = Intent(Intent.ACTION_SENDTO)
            .setData(Uri.parse("mailto:"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return CommandResult("Email opened.")
    }

    private fun setAlarm(context: Context): CommandResult {
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "Aurivox alarm")
        context.startActivity(i)
        return CommandResult("Alarm setup.")
    }
    private fun reminder(context: Context, msg: String, time: String): CommandResult {
        android.widget.Toast.makeText(context, "Reminder: $msg at $time", android.widget.Toast.LENGTH_SHORT).show()
        return CommandResult("Reminder noted.")
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
        return CommandResult(if (q.isNotBlank()) "Searching YouTube for $q." else "Opening YouTube.")
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
        val txt = "Storage: ${avail.roundToInt()} GB free of ${total.roundToInt()} GB."
        return CommandResult(txt)
    }

    private fun calcLocal(expr: String): CommandResult {
        return try {
            CommandResult("Result: ${SimpleCalc.eval(expr)}")
        } catch (_: Exception) {
            CommandResult("I can't calculate that.")
        }
    }
    private fun spell(word: String): CommandResult {
        return if (word.isBlank()) CommandResult("Say: spell followed by a word.")
        else CommandResult(word.toCharArray().joinToString(" "))
    }

    private fun respondToMood(mood: String, raw: String): CommandResult {
        val msg = when (mood) {
            "Positive" -> "Love that energy. Want to capture a note or queue some music?"
            "Neutral" -> "Okay. Would a quick focus timer or a short playlist help?"
            "Tired" -> "Rest matters. Want me to set a reminder for later or lower brightness?"
            "Stressed" -> "That’s tough. A brief breather might help—want a 5‑minute timer or a calming track?"
            "Low" -> "Thanks for sharing. I’m here—want a gentle playlist, a quick note, or a simple task?"
            else -> "Got it. Want a quick timer, a note, or music?"
        }
        return CommandResult(msg)
    }
}

object SimpleCalc {
    fun eval(s: String): Double = Parser(s.replace(" ", "")).parse()
    private class Parser(val s: String) {
        var i = 0
        fun parse(): Double = expr()
        private fun expr(): Double {
            var v = term()
            while (i < s.length) when (s[i]) {
                '+' -> { i++; v += term() }
                '-' -> { i++; v -= term() }
                else -> return v
            }
            return v
        }
        private fun term(): Double {
            var v = factor()
            while (i < s.length) when (s[i]) {
                '*' -> { i++; v *= factor() }
                '/' -> { i++; v /= factor() }
                else -> return v
            }
            return v
        }
        private fun factor(): Double {
            if (i < s.length && s[i] == '(') { i++; val v = expr(); i++; return v }
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            return s.substring(start, i).toDouble()
        }
    }
}

object Notes {
    fun save(context: Context, text: String) {
        val prefs = context.getSharedPreferences("notes", Context.MODE_PRIVATE)
        val ex = prefs.getString("notes", "") ?: ""
        val up = if (ex.isBlank()) text else "$ex\n$text"
        prefs.edit().putString("notes", up).apply()
    }
    fun get(context: Context): String {
        val prefs = context.getSharedPreferences("notes", Context.MODE_PRIVATE)
        return prefs.getString("notes", "") ?: ""
    }
    fun clear(context: Context) {
        context.getSharedPreferences("notes", Context.MODE_PRIVATE).edit().putString("notes", "").apply()
    }
}
