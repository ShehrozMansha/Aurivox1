package com.aurivox.onlineassistant

import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody

object Utils {
    private const val API_KEY = "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"
    private val client = OkHttpClient()
    private val gson = Gson()
    private val mediaType = "application/json; charset=utf-8".toMediaType()
    private const val URL = "https://api.openai.com/v1/chat/completions"

    suspend fun interpretToIntent(raw: String): ParsedIntent = withContext(Dispatchers.IO) {
        val system = """
            You are an Android voice assistant intent parser.
            Output ONLY a single-line JSON: {"action":"...", "params":{...}} with simple string values.
            Actions include OPEN_APP, YOUTUBE_SEARCH, WHATSAPP_CHAT, WIFI_PANEL, BT_PANEL, AIRPLANE_SETTINGS, BATTERY_SAVER_SETTINGS,
            FLASHLIGHT_ON/FLASHLIGHT_OFF/FLASHLIGHT_TOGGLE, BRIGHTNESS_PANEL, VOLUME_SET, NOTIFICATION_SETTINGS, SCREENSHOT_HINT, LOCK_HINT,
            CALL_CONTACT, REDIAL_HINT, SMS_COMPOSE, EMAIL_COMPOSE, SET_ALARM, CANCEL_ALARM_HINT, SNOOZE_ALARM_HINT, LIST_ALARMS_HINT,
            SET_REMINDER, OPEN_CALENDAR, ADD_NOTE, READ_NOTES, DELETE_NOTES, TELL_TIME, TELL_DATE, BATTERY_STATUS, STORAGE_STATUS,
            MEDIA_PLAY, MEDIA_PAUSE, MEDIA_NEXT, MEDIA_PREV, MEDIA_MUTE, MEDIA_UNMUTE, CALCULATE, CONVERT, DEFINE, SPELL,
            RANDOM_NUMBER, COIN_FLIP, TELL_JOKE, TELL_QUOTE, TECH_FACT, MOTIVATION, RESEARCH, SMALLTALK_HOW, USER_MOOD, UNKNOWN.
            Understand order-free phrases: "whatsapp open", "bluetooth on", "ali call", "youtube lofi search".
            Smalltalk: "how are you" -> SMALLTALK_HOW.
            Mood: "I am tired", "feeling happy" -> USER_MOOD with params.mood and params.raw.
        """.trimIndent()
        val user = """Input: "$raw""""

        val bodyJson = gson.toJson(
            mapOf(
                "model" to "gpt-3.5-turbo",
                "messages" to listOf(
                    mapOf("role" to "system", "content" to system),
                    mapOf("role" to "user", "content" to user)
                ),
                "temperature" to 0
            )
        )

        val req = Request.Builder()
            .url(URL)
            .addHeader("Authorization", "Bearer $API_KEY")
            .post(RequestBody.create(mediaType, bodyJson))
            .build()

        val res = client.newCall(req).execute()
        val txt = res.body?.string() ?: ""

        val action = Regex(""""action"\s*:\s*"([^"]+)"""").find(txt)?.groupValues?.get(1) ?: "UNKNOWN"
        val params = mutableMapOf<String, String>()
        Regex(""""params"\s*:\s*\{([^}]+)\}""").find(txt)?.groupValues?.get(1)?.let { p ->
            Regex(""""([^"]+)"\s*:\s*"([^"]*)"""").findAll(p).forEach { m ->
                params[m.groupValues[1]] = m.groupValues[2]
            }
        }
        ParsedIntent(action, params)
    }

    suspend fun answerShortOnline(raw: String): String = withContext(Dispatchers.IO) {
        val bodyJson = gson.toJson(
            mapOf(
                "model" to "gpt-3.5-turbo",
                "messages" to listOf(
                    mapOf("role" to "system", "content" to "Reply concisely (<= 2 sentences)."),
                    mapOf("role" to "user", "content" to raw)
                ),
                "temperature" to 0.7
            )
        )

        val req = Request.Builder()
            .url(URL)
            .addHeader("Authorization", "Bearer $API_KEY")
            .post(RequestBody.create(mediaType, bodyJson))
            .build()

        client.newCall(req).execute().body?.string()?.let {
            Regex(""""content"\s*:\s*"([^"]+)"""").find(it)?.groupValues?.get(1)?.replace("\\n", " ")?.take(300)
        } ?: "Done."
    }
}
