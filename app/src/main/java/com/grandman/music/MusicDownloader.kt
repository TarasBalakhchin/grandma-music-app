package com.grandman.music

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object MusicDownloader {

    private const val FOLDER_NAME = "GranyMusic"

    // Скачивание напрямую в публичную папку Music/GranyMusic
    suspend fun downloadMp3Direct(context: Context, track: Track): Boolean = withContext(Dispatchers.IO) {
        try {
            val safeFileName = "${track.artist} - ${track.title}"
                .replace(Regex("[\\\\/:*?\"<>|]"), "")
                .take(80) + ".mp3"

            var currentUrl = track.downloadUrl
            var redirects = 0
            var connection: HttpURLConnection

            // Проходим все редиректы музыкального сайта
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

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Для Android 10+ (API 29+): пишем в публичную Music/GranyMusic через MediaStore без разрешений!
                val resolver = context.contentResolver
                val contentValues = ContentValues().apply {
                    put(MediaStore.Audio.Media.DISPLAY_NAME, safeFileName)
                    put(MediaStore.Audio.Media.TITLE, track.title)
                    put(MediaStore.Audio.Media.ARTIST, track.artist)
                    put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
                    put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/$FOLDER_NAME")
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }

                val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, contentValues)
                    ?: return@withContext false

                resolver.openOutputStream(uri)?.use { output ->
                    connection.inputStream.use { input ->
                        input.copyTo(output)
                    }
                }

                contentValues.clear()
                contentValues.put(MediaStore.Audio.Media.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)

            } else {
                // Для старых Android (9 и ниже): пишем напрямую в /storage/emulated/0/Music/GranyMusic
                val publicMusicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                val grannyDir = File(publicMusicDir, FOLDER_NAME)
                if (!grannyDir.exists()) {
                    grannyDir.mkdirs()
                }
                val outputFile = File(grannyDir, safeFileName)

                connection.inputStream.use { input ->
                    FileOutputStream(outputFile).use { output ->
                        input.copyTo(output)
                    }
                }

                // Сообщаем AIMP и системному плееру о новом файле
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(outputFile.absolutePath),
                    arrayOf("audio/mpeg"),
                    null
                )
            }

            return@withContext true
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext false
        }
    }

    // Читаем все песни из публичной папки Music/GranyMusic для вкладки «Мои песни»
    fun getDownloadedTracks(context: Context): List<LocalTrack> {
        val tracks = mutableListOf<LocalTrack>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val resolver = context.contentResolver
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.DATA
            )
            val selection = "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?"
            val selectionArgs = arrayOf("%$FOLDER_NAME%")
            val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"

            resolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)

                while (cursor.moveToNext()) {
                    val fileName = cursor.getString(nameCol) ?: continue
                    val filePath = cursor.getString(dataCol) ?: continue
                    val file = File(filePath)
                    if (file.exists()) {
                        tracks.add(
                            LocalTrack(
                                title = fileName.removeSuffix(".mp3"),
                                filePath = filePath,
                                file = file
                            )
                        )
                    }
                }
            }
        } else {
            val publicMusicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            val grannyDir = File(publicMusicDir, FOLDER_NAME)
            val files = grannyDir.listFiles { file ->
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

        return tracks
    }

    // Удаление песни из папки GranyMusic
    fun deleteTrack(context: Context, localTrack: LocalTrack): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val where = "${MediaStore.Audio.Media.DATA} = ?"
                val args = arrayOf(localTrack.filePath)
                resolver.delete(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, where, args) > 0
            } else {
                localTrack.file.delete()
            }
        } catch (e: Exception) {
            localTrack.file.delete()
        }
    }
}
