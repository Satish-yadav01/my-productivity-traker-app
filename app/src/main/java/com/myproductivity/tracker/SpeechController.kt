package com.myproductivity.tracker

import android.content.Context
import android.content.Intent
import android.os.Bundle
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

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun start() {
        if (!isAvailable()) {
            listener.onError("Speech recognition isn't available on this phone. Please type instead.")
            return
        }
        stop()
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = listener.onListening(true)
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                firstResult(partialResults)?.let { listener.onPartial(it) }
            }

            override fun onResults(results: Bundle?) {
                listener.onListening(false)
                val text = firstResult(results)
                if (text.isNullOrBlank()) listener.onError(messageFor(SpeechRecognizer.ERROR_NO_MATCH))
                else listener.onFinal(text)
            }

            override fun onError(error: Int) {
                listener.onListening(false)
                listener.onError(messageFor(error))
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

    /** Stop listening and deliver whatever was heard so far. */
    fun finishListening() {
        recognizer?.stopListening()
    }

    fun stop() {
        recognizer?.apply {
            cancel()
            destroy()
        }
        recognizer = null
        listener.onListening(false)
    }

    private fun firstResult(b: Bundle?): String? =
        b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

    private fun messageFor(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH,
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "I didn't catch that. Tap Retry mic, or type it."
        SpeechRecognizer.ERROR_AUDIO -> "Microphone problem. Try again or type it."
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition needs internet right now. You can type instead."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is off. Allow it in Settings, or type instead."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech service is busy. Try again in a moment."
        else -> "Speech recognition failed. Tap Retry mic, or type it."
    }
}
