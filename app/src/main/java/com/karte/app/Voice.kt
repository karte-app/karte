package com.karte.app

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.tts.TextToSpeech
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Locale
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInitListener
import ai.pleos.playground.tts.constant.ErrorType as TtsError
import ai.pleos.playground.tts.constant.Mode as TtsMode
import ai.pleos.playground.tts.listener.OnInitializeListener as TtsInitListener

/**
 * Pleos SpeechToText / TextToSpeech SDK 연동.
 * 차량 내장(ON_DEVICE) 엔진만 사용하므로 음성이 차량 밖으로 나가지 않고, 통신이 끊겨도 기록할 수 있습니다.
 */
class Voice(context: Context, private val listener: Listener) {

    interface Listener {
        fun onListeningChanged(listening: Boolean)
        fun onPartialText(text: String)
        fun onFinalText(text: String)
        fun onVoiceError(message: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private val stt: SpeechToText? = try {
        SpeechToText(context.applicationContext, SttMode.ON_DEVICE)
    } catch (t: Throwable) {
        Log.w(TAG, "stt sdk unavailable: ${t.message}")
        null
    }
    private val tts: TextToSpeech? = try {
        TextToSpeech(context.applicationContext, TtsMode.ON_DEVICE)
    } catch (t: Throwable) {
        Log.w(TAG, "tts sdk unavailable: ${t.message}")
        null
    }

    var sttReady = false
        private set
    var ttsReady = false
        private set
    var listening = false
        private set

    private var lastText = ""
    private val timeout = Runnable { finish() }

    private val resultListener = object : ResultListener {
        override fun onReady() = Unit
        override fun onStartedRecognition() = Unit
        override fun onUpdatedEpdData(on: Long, off: Long) = Unit
        override fun onUpdatedRms(rms: Double) = Unit

        override fun onUpdated(stt: String, completed: Boolean) {
            main.post {
                if (!listening) return@post
                lastText = stt
                if (completed) finish() else listener.onPartialText(stt)
            }
        }

        override fun onEndedRecognition() {
            main.post { if (listening) finish() }
        }

        override fun onError(errorType: SttError) {
            main.post {
                if (!listening) return@post
                setListening(false)
                listener.onVoiceError("음성 인식 오류 (${errorType.name})")
            }
        }
    }

    fun start() {
        try {
            stt?.initialize(object : SttInitListener {
                override fun onSuccess() {
                    main.post {
                        sttReady = true
                        runCatching { stt?.setLocale(Locale.KOREA) }
                        runCatching { stt?.addListener(resultListener) }
                    }
                }

                override fun onFailure(errorType: SttError) {
                    Log.w(TAG, "stt init failed: ${errorType.name}")
                }
            })
        } catch (t: Throwable) {
            Log.w(TAG, "stt unavailable: ${t.message}")
        }
        try {
            tts?.initialize(object : TtsInitListener {
                override fun onSuccess() {
                    main.post {
                        ttsReady = true
                        runCatching { tts?.setLocale(Locale.KOREA) }
                    }
                }

                override fun onFailure(errorType: TtsError) {
                    Log.w(TAG, "tts init failed: ${errorType.name}")
                }
            })
        } catch (t: Throwable) {
            Log.w(TAG, "tts unavailable: ${t.message}")
        }
    }

    fun stop() {
        main.removeCallbacks(timeout)
        try {
            stt?.removeListener(resultListener)
            stt?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "stt stop: ${t.message}")
        }
        try {
            tts?.release()
        } catch (t: Throwable) {
            Log.w(TAG, "tts stop: ${t.message}")
        }
    }

    /** 차량 마이크로 인식을 시작합니다. 시작하지 못하면 false */
    fun listen(): Boolean {
        if (listening) return true
        val stt = stt
        if (stt == null || !sttReady) {
            listener.onVoiceError("음성 인식을 준비하지 못했어요")
            return false
        }
        lastText = ""
        return try {
            runCatching { tts?.stop() }
            stt.request()
            setListening(true)
            main.postDelayed(timeout, LISTEN_TIMEOUT_MS)
            true
        } catch (t: Throwable) {
            listener.onVoiceError("음성 인식을 시작하지 못했어요")
            false
        }
    }

    /** 듣기를 끝내고 지금까지 인식된 문장으로 확정합니다. */
    fun finish() {
        if (!listening) return
        setListening(false)
        runCatching { stt?.stop() }
        val text = lastText.trim()
        if (text.isEmpty()) listener.onVoiceError("인식된 내용이 없어요") else listener.onFinalText(text)
    }

    fun speak(text: String) {
        if (!ttsReady) return
        runCatching { tts?.speak(text, true) }
    }

    private fun setListening(value: Boolean) {
        listening = value
        if (!value) main.removeCallbacks(timeout)
        listener.onListeningChanged(value)
    }

    private companion object {
        const val TAG = "KarteVoice"
        const val LISTEN_TIMEOUT_MS = 12_000L
    }
}
