package com.anmol.daymark

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.util.Locale

/**
 * Listens for one spoken task in the app itself, through the phone's speech service (Google's on
 * most phones, the same engine behind the system voice popup) but without its popup, so the voice
 * button can show the sound level while you speak.
 */
internal class VoiceCapture(private val context: Context) {
    var listening by mutableStateOf(false)
        private set
    /** How loud the input is right now, 0 (silence) to 1 (loud speech). */
    var level by mutableFloatStateOf(0f)
        private set

    internal var onText: (String) -> Unit = {}
    internal var onProblem: (String) -> Unit = {}
    private var recognizer: SpeechRecognizer? = null

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        if (listening) return
        val r = recognizer ?: SpeechRecognizer.createSpeechRecognizer(context).also { recognizer = it }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            // Roughly -2 dB in a quiet room to about 10 dB speaking up close.
            override fun onRmsChanged(rmsdB: Float) { level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { level = 0f }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onResults(results: Bundle?) {
                finish()
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
                if (!text.isNullOrBlank()) onText(text) else onProblem("Didn’t catch that. Try again.")
            }
            override fun onError(error: Int) {
                finish()
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> onProblem("Didn’t catch that. Try again.")
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> onProblem("Voice needs a connection right now.")
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> onProblem("Allow the microphone to add tasks by voice.")
                    SpeechRecognizer.ERROR_CLIENT -> {} // we stopped it ourselves
                    else -> onProblem("Voice input stopped. Try again.")
                }
            }
        })
        r.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        })
        listening = true
    }

    /** Stops listening and uses what was said so far. */
    fun stop() { if (listening) recognizer?.stopListening() }

    private fun finish() {
        listening = false
        level = 0f
    }

    fun release() {
        recognizer?.destroy()
        recognizer = null
        finish()
    }
}

@Composable
internal fun rememberVoiceCapture(onText: (String) -> Unit, onProblem: (String) -> Unit): VoiceCapture {
    val context = LocalContext.current
    val voice = remember { VoiceCapture(context.applicationContext) }
    val text by rememberUpdatedState(onText)
    val problem by rememberUpdatedState(onProblem)
    voice.onText = { text(it) }
    voice.onProblem = { problem(it) }
    DisposableEffect(voice) { onDispose { voice.release() } }
    return voice
}

// Each bar's share of the sound level and its height when quiet, so the four move unevenly like a voice meter.
private val BarGain = floatArrayOf(.55f, 1f, .8f, .45f)
private val BarRest = floatArrayOf(.22f, .3f, .26f, .2f)

/** Four thin bars that rise and fall with [level] (0 to 1), drawn in [tint]. */
@Composable
internal fun VoiceBars(level: Float, tint: Color, modifier: Modifier = Modifier) {
    val heights = BarGain.indices.map { i ->
        animateFloatAsState(BarRest[i] + (1f - BarRest[i]) * (level * BarGain[i]).coerceAtMost(1f), spring(dampingRatio = .6f, stiffness = Spring.StiffnessMedium), label = "voiceBar$i").value
    }
    Canvas(modifier.size(24.dp)) {
        val bar = 3.dp.toPx()
        val gap = 2.6.dp.toPx()
        val total = 4 * bar + 3 * gap
        heights.forEachIndexed { i, h ->
            val height = size.height * h
            val x = (size.width - total) / 2 + i * (bar + gap)
            drawRoundRect(tint, Offset(x, (size.height - height) / 2), Size(bar, height), CornerRadius(bar / 2))
        }
    }
}
