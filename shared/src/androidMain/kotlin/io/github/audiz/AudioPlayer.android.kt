package io.github.audiz

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes as AndroidAudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import io.github.audiz.dsp.YamRenderersFactory
import io.github.audiz.stream.AndroidStreamMediaDataSource
import io.github.audiz.stream.LocalStreamProxy
import io.github.audiz.stream.YamExoDataSource
import io.github.audiz.util.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI

actual class AudioPlayer actual constructor() {

    private var exoPlayer: ExoPlayer? = null
    private var fadingExoPlayer: ExoPlayer? = null
    
    @Volatile private var preparingSessionId: Long = 0L
    @Volatile private var currentSessionId: Long = 0L
    
    @Volatile private var currentMediaDataSource: AndroidStreamMediaDataSource? = null
    @Volatile private var fadingMediaDataSource: AndroidStreamMediaDataSource? = null

    private var prepareTimeoutJob: Job? = null
    private var crossfadeJob: Job? = null
    private val playerScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    private var tempFile: File? = null
    private var oldTempFile: File? = null
    
    private var onCompletionListener: (() -> Unit)? = null
    private var onInterruptionListener: ((shouldPause: Boolean, canResume: Boolean) -> Unit)? = null
    private var onErrorListener: ((String) -> Unit)? = null
    private var onPlaybackStartedListener: (() -> Unit)? = null
    private var onNearEndListener: (() -> Unit)? = null
    
    private var currentVolume: Float = 1f
    
    private var audioFocusRequest: AudioFocusRequest? = null
    private var isNoisyReceiverRegistered = false

    private val becomingNoisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                pause()
                onInterruptionListener?.invoke(true, false)
            }
        }
    }

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_LOSS -> {
                pause()
                onInterruptionListener?.invoke(true, false)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                pause()
                onInterruptionListener?.invoke(true, true)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                pause()
                onInterruptionListener?.invoke(true, true)
            }
            AudioManager.AUDIOFOCUS_GAIN -> {
                onInterruptionListener?.invoke(false, true)
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        val context = AppContextHolder.appContext
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return true

        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val playbackAttributes = AndroidAudioAttributes.Builder()
                .setUsage(AndroidAudioAttributes.USAGE_MEDIA)
                .setContentType(AndroidAudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(playbackAttributes)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(audioFocusChangeListener)
                .build()
            audioFocusRequest = request
            audioManager.requestAudioFocus(request)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                audioFocusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
        return result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }

    private fun abandonAudioFocus() {
        val context = AppContextHolder.appContext
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(audioFocusChangeListener)
        }
    }

    private fun registerNoisyReceiver() {
        if (isNoisyReceiverRegistered) return
        try {
            val context = AppContextHolder.appContext
            val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            context.registerReceiver(becomingNoisyReceiver, filter)
            isNoisyReceiverRegistered = true
        } catch (_: Exception) {}
    }

    private fun unregisterNoisyReceiver() {
        if (!isNoisyReceiverRegistered) return
        try {
            val context = AppContextHolder.appContext
            context.unregisterReceiver(becomingNoisyReceiver)
        } catch (_: Exception) {
        } finally {
            isNoisyReceiverRegistered = false
        }
    }

    private fun createExoPlayer(): ExoPlayer {
        val context = AppContextHolder.appContext
        val renderersFactory = YamRenderersFactory(context)
        val player = ExoPlayer.Builder(context, renderersFactory).build()
        
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        
        player.setAudioAttributes(audioAttributes, false)
        player.setWakeMode(C.WAKE_MODE_NETWORK)
        
        return player
    }

    private fun prepareForNewPlayback(crossfadeMs: Long): Long {
        val newSessionId = ++currentSessionId
        prepareTimeoutJob?.cancel()
        prepareTimeoutJob = null

        preparingSessionId = 0L

        if (crossfadeMs <= 0L) {
            crossfadeJob?.cancel()
            crossfadeJob = null

            try {
                fadingExoPlayer?.stop()
                fadingExoPlayer?.release()
            } catch (_: Exception) {}
            fadingExoPlayer = null
            fadingMediaDataSource?.close()
            fadingMediaDataSource = null

            try {
                exoPlayer?.stop()
                exoPlayer?.release()
            } catch (_: Exception) {}
            exoPlayer = null
            currentMediaDataSource?.close()
            currentMediaDataSource = null

            oldTempFile?.delete()
            oldTempFile = null
            tempFile?.delete()
            tempFile = null
        } else {
            crossfadeJob?.cancel()
            crossfadeJob = null

            try {
                fadingExoPlayer?.stop()
                fadingExoPlayer?.release()
            } catch (_: Exception) {}
            fadingExoPlayer = null
            
            fadingMediaDataSource?.close()
            fadingMediaDataSource = currentMediaDataSource
            currentMediaDataSource = null

            oldTempFile?.delete()
            oldTempFile = null
        }
        return newSessionId
    }
    
    private fun startCrossfade(newPlayer: ExoPlayer, crossfadeMs: Long) {
        crossfadeJob?.cancel()
        val outgoing = fadingExoPlayer ?: exoPlayer
        val isOutgoingActive = outgoing != null && (outgoing.isPlaying || (outgoing.playWhenReady && outgoing.playbackState != Player.STATE_ENDED && outgoing.playbackState != Player.STATE_IDLE))
        val canCrossfade = outgoing != null && outgoing !== newPlayer && isOutgoingActive && crossfadeMs > 0L

        if (canCrossfade && outgoing != null) {
            fadingExoPlayer = outgoing
            exoPlayer = newPlayer

            val stepIntervalMs = 40L
            val steps = (crossfadeMs / stepIntervalMs).toInt().coerceAtLeast(1)

            crossfadeJob = playerScope.launch {
                for (step in 1..steps) {
                    kotlinx.coroutines.delay(stepIntervalMs)
                    val t = step.toFloat() / steps
                    val outFactor = cos(t * (PI / 2)).toFloat().coerceIn(0f, 1f)
                    val inFactor = sin(t * (PI / 2)).toFloat().coerceIn(0f, 1f)

                    val outGain = volumeToGain(currentVolume * outFactor)
                    val inGain = volumeToGain(currentVolume * inFactor)

                    try { fadingExoPlayer?.volume = outGain } catch (_: Exception) {}
                    try { newPlayer.volume = inGain } catch (_: Exception) {}
                }

                try {
                    fadingExoPlayer?.stop()
                    fadingExoPlayer?.release()
                } catch (_: Exception) {}
                fadingExoPlayer = null
                fadingMediaDataSource?.close()
                fadingMediaDataSource = null
                oldTempFile?.delete()
                oldTempFile = null

                try { newPlayer.volume = volumeToGain(currentVolume) } catch (_: Exception) {}
            }
        } else {
            try {
                outgoing?.stop()
                outgoing?.release()
            } catch (_: Exception) {}
            fadingExoPlayer = null
            fadingMediaDataSource?.close()
            fadingMediaDataSource = null
            oldTempFile?.delete()
            oldTempFile = null

            exoPlayer = newPlayer
            newPlayer.volume = volumeToGain(currentVolume)
        }
    }
    
    private fun attachListenerAndPrepare(
        player: ExoPlayer,
        sessionId: Long,
        mediaDataSource: AndroidStreamMediaDataSource?,
        crossfadeMs: Long,
        sourceAction: () -> Unit
    ) {
        var started = false
        player.addListener(object : Player.Listener {
            
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && !started) {
                    started = true
                    prepareTimeoutJob?.cancel()
                    prepareTimeoutJob = null
                    
                    if (sessionId != currentSessionId) {
                        mediaDataSource?.close()
                        try { player.release() } catch (_: Exception) {}
                        return
                    }
                    
                    currentMediaDataSource = mediaDataSource
                    
                    val wasPlaying = exoPlayer?.let { it.isPlaying || (it.playWhenReady && it.playbackState != Player.STATE_ENDED && it.playbackState != Player.STATE_IDLE) } == true
                    if (crossfadeMs > 0L && wasPlaying) {
                        player.volume = 0f
                    } else {
                        player.volume = volumeToGain(currentVolume)
                    }
                    
                    requestAudioFocus()
                    registerNoisyReceiver()

                    player.play()
                    startCrossfade(player, crossfadeMs)
                    onPlaybackStartedListener?.invoke()
                } else if (playbackState == Player.STATE_ENDED) {
                    if (exoPlayer === player) {
                        try {
                            fadingExoPlayer?.stop()
                            fadingExoPlayer?.release()
                        } catch (_: Exception) {}
                        fadingExoPlayer = null
                        oldTempFile?.delete()
                        oldTempFile = null
                        onCompletionListener?.invoke()
                    }
                }
            }
            
            override fun onPlayerError(error: PlaybackException) {
                AppLogger.e("AudioPlayerAndroid", "Ошибка ExoPlayer для сессии $sessionId: ${error.message}")
                prepareTimeoutJob?.cancel()
                prepareTimeoutJob = null
                mediaDataSource?.close()
                if (sessionId == currentSessionId) {
                    try { player.release() } catch (_: Exception) {}
                    onErrorListener?.invoke("Ошибка воспроизведения аудио: ${error.message}")
                } else {
                    try { player.release() } catch (_: Exception) {}
                }
            }
            
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady) {
                    when (reason) {
                        Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY -> {
                            onInterruptionListener?.invoke(true, false)
                        }
                        Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS -> {
                            onInterruptionListener?.invoke(true, true)
                        }
                    }
                }
            }
        })
        
        try {
            sourceAction()
            player.prepare()
        } catch (e: Exception) {
            mediaDataSource?.close()
            if (sessionId == currentSessionId) {
                onErrorListener?.invoke("Ошибка старта: ${e.message}")
            }
        }
        
        prepareTimeoutJob = playerScope.launch {
            kotlinx.coroutines.delay(12000L)
            if (sessionId == currentSessionId && !started) {
                AppLogger.e("AudioPlayerAndroid", "Таймаут буферизации для сессии $sessionId")
                mediaDataSource?.close()
                try { player.release() } catch (_: Exception) {}
                onErrorListener?.invoke("Таймаут буферизации аудиопотока")
            }
        }
    }

    actual fun playFromBytes(bytes: ByteArray) = playFromBytes(bytes, 0L)

    actual fun playFromBytes(bytes: ByteArray, crossfadeMs: Long) {
        val sessionId = prepareForNewPlayback(crossfadeMs)
        try {
            val ext = when {
                bytes.size >= 8 && bytes[4] == 'f'.code.toByte() && bytes[5] == 't'.code.toByte() &&
                        bytes[6] == 'y'.code.toByte() && bytes[7] == 'p'.code.toByte() -> ".m4a"
                bytes.size >= 4 && bytes[0] == 'f'.code.toByte() && bytes[1] == 'L'.code.toByte() &&
                        bytes[2] == 'a'.code.toByte() && bytes[3] == 'C'.code.toByte() -> ".flac"
                else -> ".mp3"
            }
            val tmp = File.createTempFile("yandex_audio_", ext).apply {
                setReadable(true, true)
                setWritable(true, true)
            }
            tmp.deleteOnExit()
            FileOutputStream(tmp).use { it.write(bytes) }
            oldTempFile?.delete()
            oldTempFile = tempFile
            tempFile = tmp

            val newPlayer = createExoPlayer()
            attachListenerAndPrepare(newPlayer, sessionId, null, crossfadeMs) {
                newPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(tmp)))
            }
        } catch (e: Exception) {
            AppLogger.e("AudioPlayerAndroid", "Ошибка воспроизведения из bytes: ${e.message}")
            if (sessionId == currentSessionId) {
                onErrorListener?.invoke("Ошибка воспроизведения аудио: ${e.message}")
            }
        }
    }

    actual fun playFromFile(filePath: String) = playFromFile(filePath, 0L)

    actual fun playFromFile(filePath: String, crossfadeMs: Long) {
        val sessionId = prepareForNewPlayback(crossfadeMs)
        try {
            val cleanPath = filePath.trim().removePrefix("local:").removePrefix("file://")
            val resolvedPath = resolveLocalPath(cleanPath)
            val file = File(resolvedPath)

            val newPlayer = createExoPlayer()
            attachListenerAndPrepare(newPlayer, sessionId, null, crossfadeMs) {
                newPlayer.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            }
        } catch (e: Exception) {
            AppLogger.e("AudioPlayerAndroid", "Ошибка воспроизведения файла: ${e.message}")
            if (sessionId == currentSessionId) {
                val msg = e.message ?: ""
                val errorMsg = if (msg.contains("EACCES") || msg.contains("Permission denied", ignoreCase = true)) {
                    "Ошибка доступа к файлу: нет разрешения на чтение хранилища. Предоставьте доступ в настройках устройства."
                } else {
                    "Ошибка воспроизведения файла: ${e.message}"
                }
                onErrorListener?.invoke(errorMsg)
            }
        }
    }

    actual fun playFromUrl(url: String) = playFromUrl(url, 0L)

    actual fun playFromUrl(url: String, crossfadeMs: Long) {
        val sessionId = prepareForNewPlayback(crossfadeMs)
        try {
            val newPlayer = createExoPlayer()
            var mediaDataSource: AndroidStreamMediaDataSource? = null
            
            if (url.startsWith("yamusic-stream://")) {
                val queryParams = parseQueryParams(url)
                val trackIdWithExt = url.substringAfter("yamusic-stream://stream/").substringBefore("?")
                val trackId = if (trackIdWithExt.contains(".")) trackIdWithExt.substringBeforeLast(".") else trackIdWithExt
                val quality = queryParams["quality"] ?: "1"
                val title = queryParams["title"] ?: ""
                val artist = queryParams["artist"] ?: ""

                val ds = LocalStreamProxy.createMediaDataSource(trackId, quality, title, artist)
                if (ds != null) {
                    mediaDataSource = ds
                    attachListenerAndPrepare(newPlayer, sessionId, ds, crossfadeMs) {
                        val factory = DataSource.Factory { YamExoDataSource(ds) }
                        val mediaSource = DefaultMediaSourceFactory(factory).createMediaSource(MediaItem.fromUri(url))
                        newPlayer.setMediaSource(mediaSource)
                    }
                } else {
                    attachListenerAndPrepare(newPlayer, sessionId, null, crossfadeMs) {
                        newPlayer.setMediaItem(MediaItem.fromUri(url))
                    }
                }
            } else {
                attachListenerAndPrepare(newPlayer, sessionId, null, crossfadeMs) {
                    newPlayer.setMediaItem(MediaItem.fromUri(url))
                }
            }
        } catch (e: Exception) {
            AppLogger.e("AudioPlayerAndroid", "Ошибка вызова playFromUrl: ${e.message}")
            if (sessionId == currentSessionId) {
                onErrorListener?.invoke("Не удалось инициализировать аудиоплеер: ${e.message}")
            }
        }
    }

    private fun parseQueryParams(url: String): Map<String, String> {
        val query = url.substringAfter("?", "")
        if (query.isBlank()) return emptyMap()
        val result = mutableMapOf<String, String>()
        query.split("&").forEach { pair ->
            val idx = pair.indexOf('=')
            if (idx != -1) {
                val k = pair.substring(0, idx)
                val v = pair.substring(idx + 1)
                result[k] = LocalStreamProxy.decodeParam(v)
            }
        }
        return result
    }

    actual fun pause() {
        try { exoPlayer?.pause() } catch (_: Exception) {}
        try { fadingExoPlayer?.pause() } catch (_: Exception) {}
        unregisterNoisyReceiver()
    }

    actual fun resume() {
        requestAudioFocus()
        registerNoisyReceiver()
        try { exoPlayer?.play() } catch (_: Exception) {}
        try { fadingExoPlayer?.play() } catch (_: Exception) {}
    }

    actual fun stop() {
        currentSessionId++
        prepareTimeoutJob?.cancel()
        prepareTimeoutJob = null

        crossfadeJob?.cancel()
        crossfadeJob = null
        try {
            fadingExoPlayer?.stop()
            fadingExoPlayer?.release()
        } catch (_: Exception) {}
        fadingExoPlayer = null
        
        fadingMediaDataSource?.close()
        fadingMediaDataSource = null
        oldTempFile?.delete()
        oldTempFile = null

        currentMediaDataSource?.close()
        currentMediaDataSource = null

        abandonAudioFocus()
        unregisterNoisyReceiver()

        try {
            exoPlayer?.stop()
            exoPlayer?.release()
        } catch (_: Exception) {}
        exoPlayer = null
        
        tempFile?.delete()
        tempFile = null
    }

    actual fun isPlaying(): Boolean {
        return try {
            val p = exoPlayer
            if (p != null) {
                p.isPlaying || (p.playWhenReady && p.playbackState != Player.STATE_ENDED && p.playbackState != Player.STATE_IDLE)
            } else {
                val f = fadingExoPlayer
                f != null && (f.isPlaying || (f.playWhenReady && f.playbackState != Player.STATE_ENDED && f.playbackState != Player.STATE_IDLE))
            }
        } catch (_: Exception) {
            false
        }
    }

    actual fun getDurationMs(): Long {
        return try {
            val d = exoPlayer?.duration ?: 0L
            if (d == C.TIME_UNSET) 0L else d
        } catch (_: Exception) {
            0L
        }
    }

    actual fun getCurrentPositionMs(): Long {
        return try {
            exoPlayer?.currentPosition ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    actual fun seekTo(positionMs: Long) {
        try {
            exoPlayer?.seekTo(positionMs.coerceAtLeast(0L))
        } catch (_: Throwable) {}
    }

    actual fun setVolume(volume: Float) {
        currentVolume = volume.coerceIn(0f, 1f)
        if (crossfadeJob?.isActive != true) {
            val gain = volumeToGain(currentVolume)
            try {
                exoPlayer?.volume = gain
            } catch (_: Exception) {}
        }
    }

    actual fun setOnCompletionListener(listener: (() -> Unit)?) {
        onCompletionListener = listener
    }

    actual fun setOnInterruptionListener(listener: ((shouldPause: Boolean, canResume: Boolean) -> Unit)?) {
        onInterruptionListener = listener
    }

    actual fun setOnErrorListener(listener: ((error: String) -> Unit)?) {
        onErrorListener = listener
    }

    actual fun setOnPlaybackStartedListener(listener: (() -> Unit)?) {
        onPlaybackStartedListener = listener
    }

    actual fun setOnNearEndListener(listener: (() -> Unit)?) {
        onNearEndListener = listener
    }

    actual fun updateNearEndThreshold(thresholdMs: Long, expectedDurationMs: Long) {
        // Handled via polling in PlaybackManager if needed
    }

    actual fun release() {
        stop()
        playerScope.cancel()
        onCompletionListener = null
        onInterruptionListener = null
        onErrorListener = null
        onPlaybackStartedListener = null
        onNearEndListener = null
    }
}
