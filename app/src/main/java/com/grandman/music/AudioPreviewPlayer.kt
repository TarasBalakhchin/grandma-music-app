package com.grandman.music

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri

class AudioPreviewPlayer {
    private var mediaPlayer: MediaPlayer? = null

    fun playOrStop(
        context: Context,
        source: String,
        onBuffering: () -> Unit = {},
        onPlaying: () -> Unit,
        onStopped: () -> Unit,
        onCompleted: () -> Unit = { onStopped() },
        onError: () -> Unit
    ) {
        stop()
        onBuffering()

        mediaPlayer = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            try {
                if (source.startsWith("http")) {
                    // Если это ссылка из интернета — передаем заголовки браузера!
                    val uri = Uri.parse(source)
                    val headers = mapOf(
                        "User-Agent" to MusicParser.USER_AGENT,
                        "Referer" to "${uri.scheme}://${uri.host}/"
                    )
                    setDataSource(context, uri, headers)
                } else {
                    // Если это уже скачанный файл на телефоне
                    setDataSource(source)
                }

                prepareAsync()

                setOnPreparedListener {
                    start()
                    onPlaying()
                }

                setOnCompletionListener {
                    onCompleted()
                }

                setOnErrorListener { _, _, _ ->
                    onError()
                    true
                }
            } catch (e: Exception) {
                e.printStackTrace()
                onError()
            }
        }
    }

    fun stop() {
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
