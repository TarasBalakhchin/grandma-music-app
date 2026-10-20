package com.grandman.music

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.URLEncoder

object MusicParser {
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    suspend fun searchTracks(query: String): List<Track> = withContext(Dispatchers.IO) {
        val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")

        // ПОПЫТКА 1: Основной сайт (Hitmotop)
        try {
            val searchUrl1 = "https://rus.hitmotop.com/search?q=$encodedQuery"
            val doc1 = Jsoup.connect(searchUrl1)
                .userAgent(USER_AGENT)
                .timeout(6_000)
                .get()

            val items1 = doc1.select("li.tracks__item")
            val tracks1 = mutableListOf<Track>()

            for (item in items1) {
                val title = item.selectFirst(".track__title")?.text()?.trim() ?: continue
                val artist = item.selectFirst(".track__desc")?.text()?.trim() ?: "Неизвестен"
                val duration = item.selectFirst(".track__fulltime")?.text()?.trim() ?: ""
                val mp3Url = item.selectFirst("a.track__download-btn")?.attr("href") ?: continue

                if (mp3Url.isNotEmpty()) {
                    tracks1.add(Track(title, artist, duration, mp3Url))
                }
            }

            if (tracks1.isNotEmpty()) {
                return@withContext tracks1
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // ПОПЫТКА 2: Резервный сайт (Muzofond)
        try {
            val searchUrl2 = "https://muzofond.cc/search/$encodedQuery"
            val doc2 = Jsoup.connect(searchUrl2)
                .userAgent(USER_AGENT)
                .timeout(6_000)
                .get()

            val items2 = doc2.select(".item.js-audioview")
            val tracks2 = mutableListOf<Track>()

            for (item in items2) {
                val title = item.selectFirst(".track")?.text()?.trim() ?: continue
                val artist = item.selectFirst(".artist")?.text()?.trim() ?: "Неизвестен"
                val duration = item.selectFirst(".time")?.text()?.trim() ?: ""
                val mp3Url = item.selectFirst(".download")?.attr("data-url") 
                    ?: item.selectFirst("a.download")?.attr("href") 
                    ?: continue

                if (mp3Url.isNotEmpty()) {
                    val fullUrl = if (mp3Url.startsWith("http")) mp3Url else "https://muzofond.cc$mp3Url"
                    tracks2.add(Track(title, artist, duration, fullUrl))
                }
            }

            if (tracks2.isNotEmpty()) {
                return@withContext tracks2
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return@withContext emptyList()
    }
}
