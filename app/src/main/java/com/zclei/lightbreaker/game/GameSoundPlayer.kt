package com.zclei.lightbreaker.game

import android.media.AudioManager
import android.media.ToneGenerator

class GameSoundPlayer {
    private val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 70)

    fun hit(intensity: Int) {
        tone.startTone(
            if (intensity >= STRONG_HIT_POWER) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_BEEP,
            if (intensity >= STRONG_HIT_POWER) 90 else 45,
        )
    }

    fun treasure() {
        tone.startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 140)
    }

    fun complete() {
        tone.startTone(ToneGenerator.TONE_CDMA_ALERT_AUTOREDIAL_LITE, 220)
    }

    fun release() {
        tone.release()
    }

    private companion object {
        const val STRONG_HIT_POWER = 190
    }
}
