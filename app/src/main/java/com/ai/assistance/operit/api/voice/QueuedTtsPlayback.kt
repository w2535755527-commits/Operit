package com.ai.assistance.operit.api.voice

import android.media.AudioAttributes
import android.media.MediaPlayer
import com.ai.assistance.operit.core.avatar.common.state.AvatarAudioMeter
import com.ai.assistance.operit.core.avatar.common.state.AvatarLipSyncBus
import com.ai.assistance.operit.util.AppLogger
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class QueuedTtsPlayback(
    private val tag: String,
    private val prepareAudioFile: suspend (Request) -> File?,
) {
    data class Request(
        val text: String,
        val rate: Float?,
        val pitch: Float?,
        val extraParams: Map<String, String>,
        val generation: Long,
        val completion: CompletableDeferred<Boolean>,
    )

    private data class PreparedRequest(
        val request: Request,
        val audioFile: File,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val speakQueue = Channel<Request>(Channel.UNLIMITED)
    private val playbackQueue = Channel<PreparedRequest>(capacity = 1)
    private val stopGeneration = AtomicLong(0)
    private val isPaused = AtomicBoolean(false)
    private val _isSpeaking = MutableStateFlow(false)
    private var mediaPlayer: MediaPlayer? = null

    val speakingStateFlow: Flow<Boolean> = _isSpeaking.asStateFlow()
    val isSpeaking: Boolean
        get() = _isSpeaking.value

    init {
        scope.launch {
            for (request in speakQueue) {
                try {
                    val audioFile = prepareAudioFile(request)
                    if (audioFile == null) {
                        request.completion.complete(false)
                    } else {
                        playbackQueue.send(PreparedRequest(request, audioFile))
                    }
                } catch (e: Exception) {
                    request.completion.completeExceptionally(e)
                }
            }
        }

        scope.launch {
            for (prepared in playbackQueue) {
                try {
                    val result = playPreparedRequest(prepared)
                    prepared.request.completion.complete(result)
                } catch (e: Exception) {
                    prepared.request.completion.completeExceptionally(e)
                }
            }
        }
    }

    suspend fun speak(
        text: String,
        interrupt: Boolean,
        rate: Float?,
        pitch: Float?,
        extraParams: Map<String, String>,
    ): Boolean = withContext(Dispatchers.IO) {
        if (interrupt) {
            clearForInterrupt()
        }

        val completion = CompletableDeferred<Boolean>()
        val request = Request(
            text = text,
            rate = rate,
            pitch = pitch,
            extraParams = extraParams,
            generation = stopGeneration.get(),
            completion = completion,
        )
        speakQueue.send(request)
        completion.await()
    }

    suspend fun stop(): Boolean = withContext(Dispatchers.IO) {
        stopGeneration.incrementAndGet()
        isPaused.set(false)
        clearPendingRequests()
        clearPendingPlayback()
        stopPlaybackOnly()
    }

    suspend fun pause(): Boolean = withContext(Dispatchers.IO) {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.pause()
                    isPaused.set(true)
                    _isSpeaking.value = false
                    return@withContext true
                }
            }
            false
        } catch (e: Exception) {
            AppLogger.e(tag, "暂停TTS播放失败", e)
            false
        }
    }

    suspend fun resume(): Boolean = withContext(Dispatchers.IO) {
        try {
            mediaPlayer?.let {
                if (!it.isPlaying) {
                    it.start()
                    isPaused.set(false)
                    _isSpeaking.value = true
                    return@withContext true
                }
            }
            false
        } catch (e: Exception) {
            AppLogger.e(tag, "恢复TTS播放失败", e)
            false
        }
    }

    fun shutdown() {
        stopGeneration.incrementAndGet()
        isPaused.set(false)
        clearPendingRequests()
        clearPendingPlayback()
        stopPlaybackOnly()
        try {
            mediaPlayer?.release()
        } catch (e: Exception) {
            AppLogger.w(tag, "释放TTS播放器失败", e)
        }
        mediaPlayer = null
        scope.cancel()
        speakQueue.close()
        playbackQueue.close()
    }

    fun isCurrent(request: Request): Boolean {
        return request.generation == stopGeneration.get()
    }

    private fun clearPendingRequests() {
        while (true) {
            val request = speakQueue.tryReceive().getOrNull() ?: break
            request.completion.complete(false)
        }
    }

    private fun clearPendingPlayback() {
        while (true) {
            val prepared = playbackQueue.tryReceive().getOrNull() ?: break
            prepared.request.completion.complete(false)
        }
    }

    private fun clearForInterrupt() {
        stopGeneration.incrementAndGet()
        isPaused.set(false)
        clearPendingRequests()
        clearPendingPlayback()
        stopPlaybackOnly()
    }

    private fun stopPlaybackOnly(): Boolean {
        return try {
            isPaused.set(false)
            // Operit patch: an explicit stop must also clear the shared lip-sync state.
            AvatarLipSyncBus.reset()
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.reset()
                _isSpeaking.value = false
                true
            } ?: false
        } catch (e: Exception) {
            AppLogger.e(tag, "停止TTS播放失败", e)
            false
        }
    }

    private suspend fun playPreparedRequest(prepared: PreparedRequest): Boolean {
        if (!isCurrent(prepared.request)) {
            return false
        }
        playAudioFile(prepared.audioFile, prepared.request.text, prepared.request.rate)
        return true
    }

    private suspend fun playAudioFile(audioFile: File, text: String, rate: Float?) {
        if (!audioFile.exists() || audioFile.length() == 0L) {
            AppLogger.e(tag, "Audio file is invalid: ${audioFile.absolutePath}")
            return
        }

        try {
            withContext(Dispatchers.IO) {
                isPaused.set(false)
                FileInputStream(audioFile).use { fis ->
                    val mp = MediaPlayer().apply {
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .build()
                        )
                        setDataSource(fis.fd)
                        prepare()
                        start()
                    }

                    mediaPlayer?.release()
                    mediaPlayer = mp
                    _isSpeaking.value = true
                    // Operit patch: MediaPlayer gives us no PCM samples, so the mouth cannot be
                    // driven by real amplitude here. Build an approximate timeline from the reply
                    // text instead and stretch it once the player reports the real duration; the
                    // avatar then at least follows the sentence rhythm instead of freezing.
                    val duration = try {
                        mp.duration.toLong()
                    } catch (_: Exception) {
                        0L
                    }
                    AvatarLipSyncBus.publishApproximateText(
                        text = text,
                        rate = rate ?: 1.0f,
                        durationMillis = if (duration > 0L) duration else 0L
                    )
                }
            }

            mediaPlayer?.let {
                while (it.isPlaying || isPaused.get()) {
                    // Operit patch: advance the phoneme cursor with the real playback clock so the
                    // mouth stays locked to the audio, and synthesize a small level so the mouth
                    // opens at all (no PCM is available on this path).
                    val position = try {
                        it.currentPosition.toLong()
                    } catch (_: Exception) {
                        -1L
                    }
                    if (position >= 0L) {
                        AvatarLipSyncBus.publishPositionMillis(position)
                        val viseme = AvatarLipSyncBus.currentViseme()
                        if (viseme != com.ai.assistance.operit.core.avatar.common.state.AvatarViseme.NONE) {
                            AvatarAudioMeter.publish(0.75f)
                        } else {
                            AvatarAudioMeter.publish(0f)
                        }
                    }
                    delay(100)
                }
            }
        } catch (e: Exception) {
            AppLogger.e(tag, "播放TTS音频失败", e)
        } finally {
            // Operit patch: clear the shared lip-sync state so a stale timeline can never leak
            // into the next utterance or into another provider.
            AvatarLipSyncBus.reset()
            _isSpeaking.value = false
            isPaused.set(false)
            mediaPlayer?.apply {
                try {
                    if (isPlaying) {
                        stop()
                    }
                } catch (_: Exception) {
                }
                try {
                    release()
                } catch (_: Exception) {
                }
            }
            mediaPlayer = null
        }
    }
}
