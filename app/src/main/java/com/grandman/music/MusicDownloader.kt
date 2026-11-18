package com.grandman.music

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
    private const val PREFS_NAME = "grandma_music_prefs"
    private const val KEY_PLAYLIST_ORDER = "playlist_order"

    // Формируем чистое имя файла без расширения
    fun getSafeTrackTitle(track: Track): String {
        return "${track.artist} - ${track.title}"
            .replace(Regex("[\\\\/:*?\"<>|]"), "")
            .trim()
            .take(80)
    }

    // Проверяем, есть ли уже такая песня в папке GranyMusic
    fun isTrackAlreadyExists(context: Context, track: Track): Boolean {
        val expectedTitle = getSafeTrackTitle(track).lowercase()
        return getDownloadedTracks(context).any { it.title.lowercase() == expectedTitle }
    }

    suspend fun downloadMp3Direct(context: Context, track: Track): Boolean = withContext(Dispatchers.IO) {
        try {
            // Защита от задвоения: если песня уже лежит в папке, не качаем её заново
            if (isTrackAlreadyExists(context, track)) {
                return@withContext true
            }

            val safeFileName = getSafeTrackTitle(track) + ".mp3"

            var currentUrl = track.downloadUrl
            var redirects = 0
            var connection: HttpURLConnection

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

    // Читаем все песни из Music/GranyMusic (и через MediaStore, и напрямую из папки, убирая дубли)
    fun getDownloadedTracks(context: Context): List<LocalTrack> {
        val trackMap = linkedMapOf<String, LocalTrack>()

        // 1. Сначала проверяем саму папку напрямую
        try {
            val publicMusicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            val grannyDir = File(publicMusicDir, FOLDER_NAME)
            if (grannyDir.exists()) {
                val files = grannyDir.listFiles { file ->
                    file.isFile && file.extension.equals("mp3", ignoreCase = true) && file.length() > 10_000
                }
                files?.sortedByDescending { it.lastModified() }?.forEach { file ->
                    // Убираем технические суффиксы вроде " (1)", если Android когда-то создал дубль
                    val cleanTitle = file.nameWithoutExtension.replace(Regex("\\s*\\(\\d+\\)$"), "")
                    if (!trackMap.containsKey(cleanTitle.lowercase())) {
                        trackMap[cleanTitle.lowercase()] = LocalTrack(
                            title = cleanTitle,
                            filePath = file.absolutePath,
                            file = file
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Дополняем через MediaStore (для Android 10+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val resolver = context.contentResolver
                val projection = arrayOf(
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
                            val cleanTitle = fileName.removeSuffix(".mp3").replace(Regex("\\s*\\(\\d+\\)$"), "")
                            if (!trackMap.containsKey(cleanTitle.lowercase())) {
                                trackMap[cleanTitle.lowercase()] = LocalTrack(
                                    title = cleanTitle,
                                    filePath = filePath,
                                    file = file
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val allTracks = trackMap.values.toList()
        return applySavedOrder(context, allTracks)
    }

    // Сохраняем порядок очереди после перетаскивания
    fun saveTrackOrder(context: Context, tracks: List<LocalTrack>) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val orderString = tracks.joinToString("|||") { it.filePath }
        prefs.edit().putString(KEY_PLAYLIST_ORDER, orderString).apply()
    }

    private fun applySavedOrder(context: Context, tracks: List<LocalTrack>): List<LocalTrack> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val orderString = prefs.getString(KEY_PLAYLIST_ORDER, null) ?: return tracks
        val savedPaths = orderString.split("|||")

        val orderMap = savedPaths.withIndex().associate { it.value to it.index }
        return tracks.sortedBy { orderMap[it.filePath] ?: Int.MAX_VALUE }
    }

    fun deleteTrack(context: Context, localTrack: LocalTrack): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val where = "${MediaStore.Audio.Media.DATA} = ?"
                val args = arrayOf(localTrack.filePath)
                resolver.delete(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, where, args)
            }
            localTrack.file.delete()
        } catch (e: Exception) {
            localTrack.file.delete()
        }
    }
}
