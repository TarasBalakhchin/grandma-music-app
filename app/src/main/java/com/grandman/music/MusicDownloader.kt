package com.grandman.music

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.widget.Toast
import java.io.File

object MusicDownloader {
    fun downloadMp3(context: Context, track: Track) {
        try {
            val safeFileName = "${track.artist} - ${track.title}"
                .replace(Regex("[\\\\/:*?\"<>|]"), "")
                .take(80) + ".mp3"

            val request = DownloadManager.Request(Uri.parse(track.downloadUrl)).apply {
                setTitle(track.title)
                setDescription("Скачивание песни: ${track.artist}")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                
                // Сохранение во внешнюю папку приложения (без запроса разрешений)
                setDestinationInExternalFilesDir(context, Environment.DIRECTORY_MUSIC, safeFileName)
                
                setAllowedOverMetered(true)
                setAllowedOverRoaming(true)
            }

            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadManager.enqueue(request)

            Toast.makeText(context, "Песня скачивается! Она появится в разделе «Мои песни».", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(context, "Ошибка при скачивании.", Toast.LENGTH_LONG).show()
        }
    }

    fun getDownloadedTracks(context: Context): List<LocalTrack> {
        val musicDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: return emptyList()
        val files = musicDir.listFiles { file ->
            file.isFile && file.extension.equals("mp3", ignoreCase = true)
        } ?: return emptyList()

        return files.sortedByDescending { it.lastModified() }.map { file ->
            LocalTrack(
                title = file.nameWithoutExtension,
                filePath = file.absolutePath,
                file = file
            )
        }
    }

    fun deleteTrack(localTrack: LocalTrack): Boolean {
        return try {
            localTrack.file.delete()
        } catch (e: Exception) {
            false
        }
    }
}
