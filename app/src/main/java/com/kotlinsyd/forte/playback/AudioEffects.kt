package com.kotlinsyd.forte.playback

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import com.kotlinsyd.forte.data.ForteSettings

/**
 * Equalizer + bass boost on the player's own audio session, using
 * Android's built-in audio effects (no library, nothing to licence).
 * Everything is fenced: devices without the effects, or sessions
 * that refuse them, simply play flat — Forte never fails over sound
 * shaping. Presets come from the device's own equalizer.
 */
object AudioEffects {

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var sessionId: Int = -1

    /** Preset names from a throwaway probe equalizer (device-defined). */
    fun presetNames(): List<String> = runCatching {
        val probe = Equalizer(0, 0)
        try {
            (0 until probe.numberOfPresets.toInt()).map { probe.getPresetName(it.toShort()) }
        } finally {
            probe.release()
        }
    }.getOrDefault(listOf("Normal", "Pop", "Rock", "Jazz", "Classical"))

    @Synchronized
    fun apply(id: Int, settings: ForteSettings) {
        if (id <= 0) return
        runCatching {
            if (sessionId != id || equalizer == null) {
                release()
                sessionId = id
                equalizer = Equalizer(0, id)
                bassBoost = BassBoost(0, id)
            }
            val eq = equalizer ?: return
            eq.enabled = settings.eqEnabled
            if (settings.eqEnabled) {
                val preset = settings.eqPreset
                    .coerceIn(0, (eq.numberOfPresets - 1).coerceAtLeast(0))
                runCatching { eq.usePreset(preset.toShort()) }
            }
            val bb = bassBoost ?: return
            bb.enabled = settings.eqEnabled && settings.bassBoost > 0
            if (settings.eqEnabled) {
                runCatching {
                    bb.setStrength(settings.bassBoost.coerceIn(0, 1000).toShort())
                }
            }
        }
    }

    @Synchronized
    private fun release() {
        runCatching { equalizer?.release() }
        runCatching { bassBoost?.release() }
        equalizer = null
        bassBoost = null
        sessionId = -1
    }
}
