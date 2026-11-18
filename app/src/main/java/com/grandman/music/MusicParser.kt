package com.grandman.music

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.URLEncoder

object MusicParser {
    const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    suspend fun searchTracks(query: String): List<Track> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return@withContext emptyList()

        // 1. Сначала ищем точный запрос на Hitmotop
        val primaryResults = fetchFromHitmotop(cleanQuery)
        if (primaryResults.isNotEmpty()) {
            return@withContext primaryResults
        }

        // 2. Если по однословному запросу (например, "Мираж") сайт ничего не отдал
        // или перекинул не туда — пробуем уточнить запрос словом "песни"
        if (!cleanQuery.contains(" ")) {
            val retryResults = fetchFromHitmotop("$cleanQuery музыка")
            if (retryResults.isNotEmpty()) {
                return@withContext retryResults
            }
        }

        // 3. Резервный сайт 1: Mp3Party
        val backup1 = fetchFromMp3Party(cleanQuery)
        if (backup1.isNotEmpty()) {
            return@withContext backup1
        }

        // 4. Резервный сайт 2: Musify
        val backup2 = fetchFromMusify(cleanQuery)
        if (backup2.isNotEmpty()) {
            return@withContext backup2
        }

        return@withContext emptyList()
    }

    private fun fetchFromHitmotop(query: String): List<Track> {
        val tracks = mutableListOf<Track>()
        val domains = listOf("https://rus.hitmotop.com", "https://hitmos.me")

        for (domain in domains) {
            try {
                val encodedQuery = URLEncoder.encode(query, "UTF-8")
                val searchUrl = "$domain/search?q=$encodedQuery"

                val doc = Jsoup.connect(searchUrl)
                    .userAgent(USER_AGENT)
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
                    .referrer("$domain/")
                    .followRedirects(true)
                    .timeout(8_000)
                    .get()

                // Ищем как стандартные элементы выдачи, так и элементы со страницы артиста
                val items = doc.select("li.tracks__item, div.track__info, li.track")

                for (item in items) {
                    val title = item.selectFirst(".track__title")?.text()?.trim() ?: continue
                    val artist = item.selectFirst(".track__desc")?.text()?.trim() ?: query
                    val duration = item.selectFirst(".track__fulltime")?.text()?.trim() ?: ""
                    val mp3Url = item.selectFirst("a.track__download-btn, a[href$=.mp3]")?.absUrl("href") ?: continue

                    if (mp3Url.startsWith("http")) {
                        tracks.add(Track(title, artist, duration, mp3Url))
                    }
                }

                if (tracks.isNotEmpty()) return tracks
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return emptyList()
    }

    private fun fetchFromMp3Party(query: String): List<Track> {
        val tracks = mutableListOf<Track>()
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val searchUrl = "https://mp3party.net/search?q=$encodedQuery"

            val doc = Jsoup.connect(searchUrl)
                .userAgent(USER_AGENT)
                .header("Accept-Language", "ru-RU,ru;q=0.9")
                .referrer("https://mp3party.net/")
                .followRedirects(true)
                .timeout(8_000)
                .get()

            val items = doc.select(".track-item, .song-item")
            for (item in items) {
                val nameText = item.selectFirst(".track-item__name, .name")?.text()?.trim() ?: continue
                val parts = nameText.split(" - ", " – ", limit = 2)
                val artist = if (parts.size > 1) parts[0].trim() else query
                val title = if (parts.size > 1) parts[1].trim() else nameText

                val mp3Url = item.selectFirst(".play-btn, [data-url]")?.attr("abs:data-url")
                    ?: item.selectFirst("a[href$=.mp3]")?.absUrl("href")
                    ?: continue

                if (mp3Url.startsWith("http")) {
                    tracks.add(Track(title, artist, "", mp3Url))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return tracks
    }

    private fun fetchFromMusify(query: String): List<Track> {
        val tracks = mutableListOf<Track>()
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val searchUrl = "https://musify.club/search?searchText=$encodedQuery"
            val doc = Jsoup.connect(searchUrl)
                .userAgent(USER_AGENT)
                .header("Accept-Language", "ru-RU,ru;q=0.9")
                .referrer("https://musify.club/")
                .followRedirects(true)
                .timeout(8_000)
                .get()

            val items = doc.select(".playlist__item")
            for (item in items) {
                val artist = item.selectFirst(".playlist__heading a")?.text()?.trim() ?: "Неизвестен"
                val title = item.selectFirst(".playlist__heading a.strong")?.text()?.trim()
                    ?: item.selectFirst(".playlist__control")?.attr("data-name") ?: continue
                val duration = item.selectFirst(".playlist__details .text-muted")?.text()?.trim() ?: ""

                val mp3Url = item.selectFirst("a[href$=.mp3]")?.absUrl("href")
                    ?: item.selectFirst(".playlist__actions a")?.absUrl("href")
                    ?: continue

                if (mp3Url.startsWith("http")) {
                    tracks.add(Track(title, artist, duration, mp3Url))
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return tracks
    }
}
