package com.myproductivity.tracker

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * Thin wrapper around Android's SpeechRecognizer.
 * Uses the en-IN model, which understands Indian-accented English and Hinglish
 * (Hindi spoken in Roman letters, e.g. "aaj 45 minute gym kiya").
 */
class SpeechController(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onListening(active: Boolean)
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError(message: String)
    }

    private var recognizer: SpeechRecognizer? = null
    private val handler = Handler(Looper.getMainLooper())
    private var keepListening = false
    private var generation = 0
    private var finishCallback: (() -> Unit)? = null

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        if (!isAvailable()) {
            listener.onError("Speech recognition isn't available on this phone. Please type instead.")
            return
        }
        keepListening = true
        generation += 1
        handler.removeCallbacksAndMessages(null)
        destroyRecognizer()
        listener.onListening(true)
        startSession(generation)
    }

    private fun startSession(sessionGeneration: Int) {
        if (!keepListening || sessionGeneration != generation) return
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                firstResult(partialResults)?.let { listener.onPartial(it) }
            }

            override fun onResults(results: Bundle?) {
                if (recognizer !== r || sessionGeneration != generation) return
                val text = firstResult(results)
                destroyRecognizer(r)
                if (!text.isNullOrBlank()) listener.onFinal(text)
                if (keepListening) scheduleRestart(sessionGeneration)
                else {
                    listener.onListening(false)
                    completeFinish()
                }
            }

            override fun onError(error: Int) {
                if (recognizer !== r || sessionGeneration != generation) return
                destroyRecognizer(r)
                if (keepListening && isRetryable(error)) {
                    scheduleRestart(sessionGeneration)
                } else {
                    keepListening = false
                    listener.onListening(false)
                    listener.onError(messageFor(error))
                    completeFinish()
                }
            }
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "en-IN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        r.startListening(intent)
    }

    private fun scheduleRestart(sessionGeneration: Int) {
        handler.postDelayed({
            if (keepListening && sessionGeneration == generation) startSession(sessionGeneration)
        }, RESTART_DELAY_MS)
    }

    private fun isRetryable(error: Int): Boolean =
        error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT

    /** Stop listening and deliver whatever was heard so far. */
    fun finishListening(onFinished: () -> Unit) {
        keepListening = false
        handler.removeCallbacksAndMessages(null)
        finishCallback = onFinished
        val activeRecognizer = recognizer
        if (activeRecognizer == null) {
            listener.onListening(false)
            completeFinish()
        } else {
            activeRecognizer.stopListening()
            handler.postDelayed({
                if (finishCallback != null) {
                    destroyRecognizer(activeRecognizer)
                    listener.onListening(false)
                    completeFinish()
                }
            }, FINISH_TIMEOUT_MS)
        }
    }

    fun stop() {
        keepListening = false
        generation += 1
        handler.removeCallbacksAndMessages(null)
        finishCallback = null
        destroyRecognizer()
        listener.onListening(false)
    }

    private fun completeFinish() {
        val callback = finishCallback ?: return
        finishCallback = null
        handler.removeCallbacksAndMessages(null)
        callback()
    }

    private fun destroyRecognizer(expected: SpeechRecognizer? = recognizer) {
        if (expected == null) return
        if (recognizer === expected) recognizer = null
        expected.apply {
            cancel()
            destroy()
        }
    }

    private fun firstResult(b: Bundle?): String? =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private companion object {
        const val RESTART_DELAY_MS = 350L
        const val FINISH_TIMEOUT_MS = 1500L
    }

    private fun messageFor(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->         "I didn't catch that. Tap the microphone on the Today screen to try again, or type it."
        SpeechRecognizer.ERROR_AUDIO -> "Microphone problem. Try again or type it."
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition needs internet right now. You can type instead."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is off. Allow it in Settings, or type instead."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech service is busy. Try again in a moment."
        else -> "Speech recognition failed. Tap the microphone on the Today screen to try again, or type it."
    }
}
