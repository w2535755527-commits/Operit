package com.ai.assistance.operit.core.avatar.common.state

import kotlin.math.pow

/**
 * Operit patch: in-memory audio amplitude meter.
 *
 * A [android.media.audiofx.Visualizer] is unreliable for the TTS pipeline because the
 * audio session id is only known after the player starts, it fails when another app owns
 * the session, and it is unavailable on some emulators. Instead every audio source that
 * has the PCM samples (local VITS playback) or the decoded stream (streaming players)
 * pushes the amplitude it already computed into this meter. The avatar composer then
 * samples [RealtimeAvatarStateComposer.fromEmotion] with a live level.
 *
 * The class is intentionally tiny, lock-light, and safe to call from the audio thread.
 */
object AvatarAudioMeter {

    /** Amplitude below this level is treated as silence so the mouth fully closes. */
    const val SILENCE_FLOOR = 0.02f

    @Volatile
    private var level: Float = 0f

    @Volatile
    private var lastUpdateNanos: Long = 0L

    /** How long a sample stays valid. Stale samples decay to silence. */
    private const val STALE_AFTER_NANOS = 400_000_000L

    /** Publishes the current normalized amplitude (0..1) from any audio source. */
    fun publish(normalizedLevel: Float) {
        level = if (normalizedLevel.isFinite()) normalizedLevel.coerceIn(0f, 1f) else 0f
        lastUpdateNanos = System.nanoTime()
    }

    /**
     * Converts a signed 16-bit PCM chunk into a normalized 0..1 amplitude and publishes it.
     * Uses RMS with a mild gamma so quiet speech still moves the mouth.
     */
    fun publishPcm16(samples: ShortArray, offset: Int = 0, length: Int = samples.size - offset) {
        if (length <= 0 || offset < 0 || offset >= samples.size) {
            publish(0f)
            return
        }
        val end = (offset + length).coerceAtMost(samples.size)
        var sumSquares = 0.0
        for (i in offset until end) {
            val v = samples[i].toDouble()
            sumSquares += v * v
        }
        val count = (end - offset).coerceAtLeast(1)
        val rms = kotlin.math.sqrt(sumSquares / count) / 32768.0
        publish(linearToNormalized(rms))
    }

    /** Maps a linear 0..1 RMS value to a perceptually friendlier 0..1 level. */
    fun linearToNormalized(rms: Double): Float {
        if (rms <= 0.0) return 0f
        val scaled = (rms / 0.32).coerceIn(0.0, 1.0)
        return scaled.pow(0.6).toFloat().coerceIn(0f, 1f)
    }

    /** Returns the most recent amplitude, or 0 once the sample has gone stale. */
    fun currentLevel(): Float {
        val last = lastUpdateNanos
        if (last == 0L) return 0f
        if (System.nanoTime() - last > STALE_AFTER_NANOS) return 0f
        return level
    }

    /** Clears the meter, e.g. when playback stops. */
    fun reset() {
        level = 0f
        lastUpdateNanos = 0L
    }
}