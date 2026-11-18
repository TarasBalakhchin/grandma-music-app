package com.grandman.music

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

class AudioPreviewPlayer {
    private var mediaPlayer: MediaPlayer? = null
    private val scope = CoroutineScope(Dispatchers.Main)
    private var isPrepared = false

    fun playOrStop(
        context: Context,
        source: String,
        onBuffering: () -> Unit = {},
        onPlaying: (durationMs: Int) -> Unit,
        onStopped: () -> Unit,
        onCompleted: () -> Unit = { onStopped() },
        onError: () -> Unit
    ) {
        stop()
        onBuffering()

        scope.launch {
            val finalSource = if (source.startsWith("http")) {
                resolveFinalUrl(source)
            } else {
                source
            }

            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                try {
                    if (finalSource.startsWith("http")) {
                        val uri = Uri.parse(finalSource)
                        val headers = mapOf(
                            "User-Agent" to MusicParser.USER_AGENT,
                            "Referer" to "${uri.scheme}://${uri.host}/"
                        )
                        setDataSource(context, uri, headers)
                    } else {
                        setDataSource(finalSource)
                    }

                    prepareAsync()

                    setOnPreparedListener { mp ->
                        isPrepared = true
                        mp.start()
                        onPlaying(mp.duration.coerceAtLeast(0))
                    }

                    setOnCompletionListener {
                        isPrepared = false
                        onCompleted()
                    }

                    setOnErrorListener { _, _, _ ->
                        isPrepared = false
                        onError()
                        true
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    isPrepared = false
                    onError()
                }
            }
        }
    }

    fun pause() {
        try {
            if (isPrepared && mediaPlayer?.isPlaying == true) {
                mediaPlayer?.pause()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun resume() {
        try {
            if (isPrepared && mediaPlayer?.isPlaying == false) {
                mediaPlayer?.start()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun seekTo(positionMs: Int) {
        try {
            if (isPrepared) {
                val safePos = positionMs.coerceIn(0, getDuration())
                mediaPlayer?.seekTo(safePos)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getCurrentPosition(): Int {
        return try {
            if (isPrepared) mediaPlayer?.currentPosition ?: 0 else 0
        } catch (e: Exception) {
            0
        }
    }

    fun getDuration(): Int {
        return try {
            if (isPrepared) mediaPlayer?.duration?.coerceAtLeast(0) ?: 0 else 0
        } catch (e: Exception) {
            0
        }
    }

    private suspend fun resolveFinalUrl(urlStr: String): String = withContext(Dispatchers.IO) {
        var currentUrl = urlStr
        var redirects = 0
        try {
            while (redirects < 5) {
                val url = URL(currentUrl)
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 8_000
                    readTimeout = 8_000
                    requestMethod = "GET"
                    setRequestProperty("User-Agent", MusicParser.USER_AGENT)
                    setRequestProperty("Referer", "${url.protocol}://${url.host}/")
                }
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location") ?: break
                    currentUrl = URL(url, location).toString()
                    conn.disconnect()
                    redirects++
                } else {
                    conn.disconnect()
                    break
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return@withContext currentUrl
    }

    fun stop() {
        isPrepared = false
        try {
            mediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            mediaPlayer = null
        }
    }
}
