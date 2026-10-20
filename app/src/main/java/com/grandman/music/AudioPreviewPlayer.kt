package com.grandman.music

import android.media.AudioAttributes
import android.media.MediaPlayer

class AudioPreviewPlayer {
    private var mediaPlayer: MediaPlayer? = null

    fun playOrStop(
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
                setDataSource(source)
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
