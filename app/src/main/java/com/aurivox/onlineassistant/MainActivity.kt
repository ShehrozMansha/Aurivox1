package com.aurivox.onlineassistant

import android.Manifest
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.animation.LinearInterpolator
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.util.Locale
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.content.Intent
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private lateinit var headerQuotes: TextView
    private lateinit var orbImage: ImageView
    private lateinit var voiceStatus: TextView
    private lateinit var inputBar: EditText
    private lateinit var btnSend: Button

    private var sr: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null

    private val handler = Handler(Looper.getMainLooper())
    private var qi = 0

    private var commandsSinceMoodPrompt = 0
    private val moodPromptInterval = 4
    private var lastMoodPromptTime = 0L
    private val moodPromptCooldownMs = 3 * 60 * 1000L

    private val reqAudio = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val reqCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val reqContacts = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val reqCall = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val reqSms = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        headerQuotes = findViewById(R.id.headerQuotes)
        orbImage = findViewById(R.id.orbImage)
        voiceStatus = findViewById(R.id.voiceStatus)
        inputBar = findViewById(R.id.inputBar)
        btnSend = findViewById(R.id.btnSend)

        reqAudio.launch(Manifest.permission.RECORD_AUDIO)
        reqCamera.launch(Manifest.permission.CAMERA)
        reqContacts.launch(Manifest.permission.READ_CONTACTS)
        reqCall.launch(Manifest.permission.CALL_PHONE)
        reqSms.launch(Manifest.permission.SEND_SMS)

        tts = TextToSpeech(this) { if (it == TextToSpeech.SUCCESS) tts?.language = Locale.getDefault() }

        btnSend.setOnClickListener {
            val text = inputBar.text?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) handleCommand(text)
        }

        startQuoteRotation()
        startOrbMotion()
    }

    override fun onResume() {
        super.onResume()
        startListening()
    }

    override fun onPause() {
        super.onPause()
        stopListening()
    }

    private fun startOrbMotion() {
        orbImage.animate().rotationBy(360f).setDuration(6000).setInterpolator(LinearInterpolator()).withEndAction { startOrbMotion() }.start()
        orbImage.animate().scaleX(1.08f).scaleY(1.08f).setDuration(1000).withEndAction {
            orbImage.animate().scaleX(1f).scaleY(1f).setDuration(1000).start()
        }.start()
    }

    private fun startQuoteRotation() {
        val arr = resources.getStringArray(R.array.quotes)
        headerQuotes.text = arr[qi % arr.size]
        handler.postDelayed(object : Runnable {
            override fun run() {
                qi++; headerQuotes.text = arr[qi % arr.size]; handler.postDelayed(this, 4500)
            }
        }, 4500)
    }

    private fun startListening() {
        voiceStatus.text = "Listening…"
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            speak("Voice recognition not available")
            return
        }
        if (sr != null) return

        sr = SpeechRecognizer.createSpeechRecognizer(this)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        sr?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { voiceStatus.text = "Processing…" }
            override fun onError(error: Int) { voiceStatus.text = "Restarting…"; restartListening() }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onResults(results: Bundle) {
                val texts = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val spoken = texts?.firstOrNull().orEmpty()
                if (spoken.isNotBlank()) handleCommand(spoken)
                restartListening()
            }
        })
        sr?.startListening(intent)
    }

    private fun restartListening() { stopListening(); Handler(mainLooper).postDelayed({ startListening() }, 200) }
    private fun stopListening() { try { sr?.stopListening(); sr?.destroy() } catch (_: Exception) {}; sr = null; voiceStatus.text = "Idle" }

    private fun handleCommand(raw: String) {
        lifecycleScope.launch {
            voiceStatus.text = "Processing…"

            val parsed = try { Utils.interpretToIntent(raw) } catch (_: Exception) { IntentParser.parse(raw) }
            val result = CommandExecutor.execute(this@MainActivity, parsed)

            voiceStatus.text = "Speaking…"
            speak(result.spoken)

            if (parsed.action in listOf("UNKNOWN", "RESEARCH")) {
                try { speak(Utils.answerShortOnline("Keep it short and helpful:\n$raw")) } catch (_: Exception) {}
            }

            val now = System.currentTimeMillis()
            if (parsed.action !in listOf("SMALLTALK_HOW", "USER_MOOD")) {
                commandsSinceMoodPrompt++
                val cooledDown = now - lastMoodPromptTime > moodPromptCooldownMs
                val hitInterval = commandsSinceMoodPrompt >= moodPromptInterval
                val randomChance = Random.nextInt(0, 5) == 0

                if ((hitInterval || randomChance) && cooledDown) {
                    speak(randomMoodPrompt())
                    commandsSinceMoodPrompt = 0
                    lastMoodPromptTime = now
                }
            } else {
                if (Random.nextBoolean()) speak("Want a quick timer, a note, or music?")
                commandsSinceMoodPrompt = 0
                lastMoodPromptTime = now
            }

            voiceStatus.text = "Listening…"
        }
    }

    private fun randomMoodPrompt(): String {
        val prompts = listOf(
            "How’s your mood right now?",
            "Feeling focused, relaxed, or something else?",
            "Quick check‑in—how are you feeling?",
            "What’s your vibe today?"
        )
        return prompts.random()
    }

    private fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "aurivox_tts")
    }

    override fun onDestroy() {
        super.onDestroy()
        tts?.shutdown()
        stopListening()
    }
}
