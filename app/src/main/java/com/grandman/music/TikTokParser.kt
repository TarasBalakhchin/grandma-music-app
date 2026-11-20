package com.grandman.music

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object TikTokParser {

    // Вырезаем чистую ссылку на TikTok, даже если она скопирована вместе с описанием ролика
    fun extractTikTokUrl(rawText: String): String? {
        val regex = Regex("""https?://(?:www\.|vm\.|vt\.|m\.)?tiktok\.com/[^\s]+""", RegexOption.IGNORE_CASE)
        return regex.find(rawText)?.value
    }

    fun isTikTokUrl(rawText: String): Boolean {
        return extractTikTokUrl(rawText) != null
    }

    suspend fun fetchAudioFromTikTok(rawInput: String): Track? = withContext(Dispatchers.IO) {
        val cleanUrl = extractTikTokUrl(rawInput) ?: return@withContext null

        try {
            val encodedUrl = URLEncoder.encode(cleanUrl, "UTF-8")
            val apiUrl = URL("https://www.tikwm.com/api/?url=$encodedUrl")

            val conn = (apiUrl.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 10_000
                setRequestProperty("User-Agent", MusicParser.USER_AGENT)
                setRequestProperty("Accept", "application/json")
            }

            if (conn.responseCode != HttpURLConnection.HTTP_OK) return@withContext null

            val responseText = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(responseText)

            if (json.optInt("code", -1) != 0) return@withContext null

            val data = json.optJSONObject("data") ?: return@withContext null
            val musicInfo = data.optJSONObject("music_info")

            // Достаем прямую ссылку на аудиодорожку
            var mp3Url = data.optString("music", "")
            if (mp3Url.isEmpty() && musicInfo != null) {
                mp3Url = musicInfo.optString("play", "")
            }
            if (mp3Url.isEmpty()) return@withContext null

            if (!mp3Url.startsWith("http")) {
                mp3Url = "https://www.tikwm.com$mp3Url"
            }

            // Берем название трека и автора (или описание видео, если звук оригинальный)
            val rawTitle = musicInfo?.optString("title", "")?.takeIf { it.isNotBlank() }
                ?: data.optString("title", "").takeIf { it.isNotBlank() }?.take(45)
                ?: "Звук из TikTok"

            val author = musicInfo?.optString("author", "")?.takeIf { it.isNotBlank() }
                ?: data.optJSONObject("author")?.optString("nickname", "")?.takeIf { it.isNotBlank() }
                ?: "TikTok"

            val durationSec = musicInfo?.optInt("duration", 0) ?: data.optInt("duration", 0)
            val durationFormatted = if (durationSec > 0) {
                "%02d:%02d".format(durationSec / 60, durationSec % 60)
            } else {
                ""
            }

            return@withContext Track(
                title = rawTitle.trim(),
                artist = author.trim(),
                duration = durationFormatted,
                downloadUrl = mp3Url
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext null
        }
    }
}
