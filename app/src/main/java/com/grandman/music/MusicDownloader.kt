package com.grandman.music

import android.content.Context
import android.os.Environment
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object MusicDownloader {

    // Надежное прямое скачивание файла с поддержкой редиректов и заголовков браузера
    suspend fun downloadMp3Direct(context: Context, track: Track): Boolean = withContext(Dispatchers.IO) {
        try {
            val safeFileName = "${track.artist} - ${track.title}"
                .replace(Regex("[\\\\/:*?\"<>|]"), "")
                .take(80) + ".mp3"

            val musicDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: return@withContext false
            if (!musicDir.exists()) {
                musicDir.mkdirs()
            }
            val outputFile = File(musicDir, safeFileName)

            var currentUrl = track.downloadUrl
            var redirects = 0
            var connection: HttpURLConnection

            // Обрабатываем возможные редиректы с https на http вручную
            while (true) {
                val url = URL(currentUrl)
                connection = (url.openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = false
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    setRequestProperty("User-Agent", MusicParser.USER_AGENT)
                    setRequestProperty("Referer", "${url.protocol}://${url.host}/")
                }

                val responseCode = connection.responseCode
                if (responseCode in 300..399) {
                    val location = connection.getHeaderField("Location") ?: break
                    currentUrl = URL(url, location).toString()
                    connection.disconnect()
                    redirects++
                    if (redirects > 5) break
                } else {
                    break
                }
            }

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                return@withContext false
            }

            connection.inputStream.use { input ->
                FileOutputStream(outputFile).use { output ->
                    input.copyTo(output)
                }
            }
            return@withContext true
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext false
        }
    }

    fun getDownloadedTracks(context: Context): List<LocalTrack> {
        val musicDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC) ?: return emptyList()
        val files = musicDir.listFiles { file ->
            file.isFile && file.extension.equals("mp3", ignoreCase = true) && file.length() > 10_000
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
