package com.grandman.music

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Форматирование миллисекунд в понятные минуты и секунды (например, 01:25)
private fun formatTime(ms: Int): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}

@Composable
fun GrandmaMusicApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var currentTab by remember { mutableIntStateOf(0) }

    var searchQuery by remember { mutableStateOf("") }
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    val downloadingUrls = remember { mutableStateListOf<String>() }
    val downloadedUrls = remember { mutableStateListOf<String>() }

    var localTracks by remember { mutableStateOf<List<LocalTrack>>(emptyList()) }

    val audioPlayer = remember { AudioPreviewPlayer() }
    var currentlyPlayingSource by remember { mutableStateOf<String?>(null) }
    var isBufferingSource by remember { mutableStateOf<String?>(null) }

    // Состояния для мини-плеера в «Моих песнях»
    var currentLocalIndex by remember { mutableIntStateOf(-1) }
    var isPaused by remember { mutableStateOf(false) }
    var currentPositionMs by remember { mutableIntStateOf(0) }
    var totalDurationMs by remember { mutableIntStateOf(0) }
    var isUserDraggingSlider by remember { mutableStateOf(false) }
    var sliderDragValue by remember { mutableFloatStateOf(0f) }

    DisposableEffect(Unit) {
        onDispose { audioPlayer.stop() }
    }

    // Таймер обновления ползунка каждые 300 мс, пока играет песня
    LaunchedEffect(currentlyPlayingSource, isPaused, isUserDraggingSlider) {
        while (currentlyPlayingSource != null && !isPaused) {
            if (!isUserDraggingSlider) {
                currentPositionMs = audioPlayer.getCurrentPosition()
                val dur = audioPlayer.getDuration()
                if (dur > 0) totalDurationMs = dur
            }
            delay(300)
        }
    }

    fun stopAllPlayback() {
        audioPlayer.stop()
        currentlyPlayingSource = null
        isBufferingSource = null
        currentLocalIndex = -1
        isPaused = false
        currentPositionMs = 0
        totalDurationMs = 0
    }

    fun playLocalTrackAtIndex(index: Int) {
        if (index < 0 || index >= localTracks.size) {
            stopAllPlayback()
            return
        }
        val trackToPlay = localTracks[index]
        currentLocalIndex = index
        isPaused = false
        currentPositionMs = 0

        audioPlayer.playOrStop(
            context = context,
            source = trackToPlay.filePath,
            onPlaying = { duration ->
                isBufferingSource = null
                currentlyPlayingSource = trackToPlay.filePath
                totalDurationMs = duration
            },
            onStopped = {
                stopAllPlayback()
            },
            onCompleted = {
                // Автопереход к следующей песне
                if (index + 1 < localTracks.size) {
                    playLocalTrackAtIndex(index + 1)
                } else {
                    stopAllPlayback()
                }
            },
            onError = {
                stopAllPlayback()
                Toast.makeText(context, "Не удалось открыть файл", Toast.LENGTH_SHORT).show()
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // ВЕРХНИЕ ВКЛАДКИ
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = {
                    stopAllPlayback()
                    currentTab = 0
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (currentTab == 0) Color(0xFF1565C0) else Color(0xFFB0BEC5)
                ),
                modifier = Modifier.weight(1f).height(64.dp)
            ) {
                Text("🔍 ПОИСК", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = if (currentTab == 0) Color.White else Color.Black)
            }

            Button(
                onClick = {
                    stopAllPlayback()
                    localTracks = MusicDownloader.getDownloadedTracks(context)
                    currentTab = 1
                },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (currentTab == 1) Color(0xFF2E7D32) else Color(0xFFB0BEC5)
                ),
                modifier = Modifier.weight(1f).height(64.dp)
            ) {
                Text("🎵 МОИ ПЕСНИ", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = if (currentTab == 1) Color.White else Color.Black)
            }
        }

        // ВКЛАДКА 0: ПОИСК В ИНТЕРНЕТЕ
        if (currentTab == 0) {
            fun performSearch(query: String) {
                if (query.isNotBlank()) {
                    stopAllPlayback()
                    isLoading = true
                    scope.launch {
                        tracks = MusicParser.searchTracks(query)
                        isLoading = false
                    }
                }
            }

            val voiceLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartActivityForResult()
            ) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    val spokenText = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
                    if (!spokenText.isNullOrBlank()) {
                        searchQuery = spokenText
                        performSearch(spokenText)
                    }
                }
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Напишите песню или артиста", fontSize = 18.sp) },
                textStyle = LocalTextStyle.current.copy(fontSize = 22.sp),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { performSearch(searchQuery) }, modifier = Modifier.weight(1f).height(60.dp)) {
                    Text("НАЙТИ", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = {
                        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "ru-RU")
                            putExtra(RecognizerIntent.EXTRA_PROMPT, "Скажите название песни...")
                        }
                        voiceLauncher.launch(intent)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2)),
                    modifier = Modifier.weight(1f).height(60.dp)
                ) {
                    Text("🎤 ГОЛОСОМ", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(modifier = Modifier.size(64.dp))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Ищем песни, подождите...", fontSize = 22.sp)
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxSize()) {
                    items(tracks) { track ->
                        val isDownloading = downloadingUrls.contains(track.downloadUrl)
                        val isAlreadyDownloaded = downloadedUrls.contains(track.downloadUrl)
                        val isPlaying = currentlyPlayingSource == track.downloadUrl
                        val isBuffering = isBufferingSource == track.downloadUrl

                        Card(
                            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                            border = if (isPlaying) BorderStroke(3.dp, Color(0xFFE65100)) else null,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(track.title, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                Text(
                                    text = if (track.duration.isNotBlank()) "${track.artist} (${track.duration})" else track.artist,
                                    fontSize = 18.sp,
                                    color = Color.DarkGray,
                                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                                )

                                Button(
                                    onClick = {
                                        if (isPlaying || isBuffering) {
                                            stopAllPlayback()
                                        } else {
                                            audioPlayer.playOrStop(
                                                context = context,
                                                source = track.downloadUrl,
                                                onBuffering = {
                                                    currentlyPlayingSource = null
                                                    isBufferingSource = track.downloadUrl
                                                },
                                                onPlaying = { _ ->
                                                    isBufferingSource = null
                                                    currentlyPlayingSource = track.downloadUrl
                                                },
                                                onStopped = {
                                                    isBufferingSource = null
                                                    currentlyPlayingSource = null
                                                },
                                                onError = {
                                                    isBufferingSource = null
                                                    currentlyPlayingSource = null
                                                    Toast.makeText(context, "Не удалось включить песню", Toast.LENGTH_SHORT).show()
                                                }
                                            )
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = when {
                                            isPlaying -> Color(0xFFE65100)
                                            isBuffering -> Color(0xFF757575)
                                            else -> Color(0xFF1565C0)
                                        }
                                    ),
                                    modifier = Modifier.fillMaxWidth().height(54.dp)
                                ) {
                                    Text(
                                        text = when {
                                            isBuffering -> "⏳ ЗАГРУЖАЕМ..."
                                            isPlaying -> "⏹ ОСТАНОВИТЬ"
                                            else -> "▶ ПОСЛУШАТЬ"
                                        },
                                        fontSize = 19.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                Button(
                                    onClick = {
                                        if (isPlaying) {
                                            stopAllPlayback()
                                        }
                                        downloadingUrls.add(track.downloadUrl)
                                        scope.launch {
                                            val success = MusicDownloader.downloadMp3Direct(context, track)
                                            downloadingUrls.remove(track.downloadUrl)
                                            if (success) {
                                                downloadedUrls.add(track.downloadUrl)
                                                Toast.makeText(context, "Песня сохранена в «Мои песни»!", Toast.LENGTH_LONG).show()
                                            } else {
                                                Toast.makeText(context, "Не удалось скачать этот файл, попробуйте другой вариант ниже.", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    },
                                    enabled = !isAlreadyDownloaded && !isDownloading,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF2E7D32),
                                        disabledContainerColor = if (isDownloading) Color(0xFFFFB74D) else Color(0xFFA5D6A7)
                                    ),
                                    modifier = Modifier.fillMaxWidth().height(56.dp)
                                ) {
                                    Text(
                                        text = when {
                                            isDownloading -> "⏳ СКАЧИВАЕМ... ЖДИТЕ"
                                            isAlreadyDownloaded -> "✓ УЖЕ В «МОИХ ПЕСНЯХ»"
                                            else -> "⬇ СКАЧАТЬ В ТЕЛЕФОН"
                                        },
                                        fontSize = 19.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isAlreadyDownloaded || isDownloading) Color.Black else Color.White
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ВКЛАДКА 1: МОИ ПЕСНИ + ПЛЕЕР С ПЕРЕМОТКОЙ
        if (currentTab == 1) {
            if (localTracks.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("У вас пока нет скачанных песен.\nПерейдите в «ПОИСК» сверху.", fontSize = 22.sp, fontWeight = FontWeight.Medium)
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Список скачанных песен
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    ) {
                        itemsIndexed(localTracks) { index, localTrack ->
                            val isThisTrackActive = currentlyPlayingSource == localTrack.filePath

                            Card(
                                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                                border = if (isThisTrackActive) BorderStroke(3.dp, Color(0xFF2E7D32)) else null,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(16.dp)) {
                                    Text(localTrack.title, fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 12.dp))

                                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Button(
                                            onClick = {
                                                if (isThisTrackActive) {
                                                    if (isPaused) {
                                                        audioPlayer.resume()
                                                        isPaused = false
                                                    } else {
                                                        audioPlayer.pause()
                                                        isPaused = true
                                                    }
                                                } else {
                                                    playLocalTrackAtIndex(index)
                                                }
                                            },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = when {
                                                    isThisTrackActive && !isPaused -> Color(0xFFE65100)
                                                    isThisTrackActive && isPaused -> Color(0xFF1565C0)
                                                    else -> Color(0xFF2E7D32)
                                                }
                                            ),
                                            modifier = Modifier.weight(1f).height(54.dp)
                                        ) {
                                            Text(
                                                text = when {
                                                    isThisTrackActive && !isPaused -> "⏸ ПАУЗА"
                                                    isThisTrackActive && isPaused -> "▶ ПРОДОЛЖИТЬ"
                                                    else -> "▶ ВКЛЮЧИТЬ"
                                                },
                                                fontSize = 19.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }

                                        OutlinedButton(
                                            onClick = {
                                                if (isThisTrackActive) {
                                                    stopAllPlayback()
                                                }
                                                MusicDownloader.deleteTrack(context, localTrack)
                                                localTracks = MusicDownloader.getDownloadedTracks(context)
                                            },
                                            modifier = Modifier.height(54.dp)
                                        ) {
                                            Text("🗑", fontSize = 20.sp, color = Color(0xFFC62828))
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // НИЖНЯЯ ПАНЕЛЬ ПЛЕЕРА С ПЕРЕМОТКОЙ (показывается, когда выбрана песня)
                    if (currentLocalIndex in localTracks.indices && currentlyPlayingSource != null) {
                        val activeTrack = localTracks[currentLocalIndex]
                        Spacer(modifier = Modifier.height(10.dp))

                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
                            border = BorderStroke(2.dp, Color(0xFF2E7D32)),
                            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp)) {
                                Text(
                                    text = "Сейчас играет: ${activeTrack.title}",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )

                                // Ползунок перемотки
                                val maxDuration = totalDurationMs.coerceAtLeast(1).toFloat()
                                val sliderVal = if (isUserDraggingSlider) {
                                    sliderDragValue
                                } else {
                                    currentPositionMs.toFloat().coerceIn(0f, maxDuration)
                                }

                                Slider(
                                    value = sliderVal,
                                    onValueChange = { newVal ->
                                        isUserDraggingSlider = true
                                        sliderDragValue = newVal
                                    },
                                    onValueChangeFinished = {
                                        audioPlayer.seekTo(sliderDragValue.toInt())
                                        currentPositionMs = sliderDragValue.toInt()
                                        isUserDraggingSlider = false
                                    },
                                    valueRange = 0f..maxDuration,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                // Время слева и справа под ползунком
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(formatTime(sliderVal.toInt()), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                    Text(formatTime(totalDurationMs), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                }

                                Spacer(modifier = Modifier.height(8.dp))

                                // Крупные кнопки управления: -10 сек, Пауза/Плей, +10 сек, Следующая
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            val newPos = (audioPlayer.getCurrentPosition() - 10_000).coerceAtLeast(0)
                                            audioPlayer.seekTo(newPos)
                                            currentPositionMs = newPos
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF455A64)),
                                        contentPadding = PaddingValues(0.dp),
                                        modifier = Modifier.weight(1f).height(52.dp)
                                    ) {
                                        Text("⏪ -10с", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                    }

                                    Button(
                                        onClick = {
                                            if (isPaused) {
                                                audioPlayer.resume()
                                                isPaused = false
                                            } else {
                                                audioPlayer.pause()
                                                isPaused = true
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = if (isPaused) Color(0xFF2E7D32) else Color(0xFFE65100)
                                        ),
                                        contentPadding = PaddingValues(0.dp),
                                        modifier = Modifier.weight(1.3f).height(52.dp)
                                    ) {
                                        Text(
                                            text = if (isPaused) "▶ ИГРАТЬ" else "⏸ ПАУЗА",
                                            fontSize = 17.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }

                                    Button(
                                        onClick = {
                                            val newPos = (audioPlayer.getCurrentPosition() + 10_000).coerceAtMost(totalDurationMs)
                                            audioPlayer.seekTo(newPos)
                                            currentPositionMs = newPos
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF455A64)),
                                        contentPadding = PaddingValues(0.dp),
                                        modifier = Modifier.weight(1f).height(52.dp)
                                    ) {
                                        Text("+10с ⏩", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                    }

                                    Button(
                                        onClick = {
                                            if (currentLocalIndex + 1 < localTracks.size) {
                                                playLocalTrackAtIndex(currentLocalIndex + 1)
                                            } else {
                                                playLocalTrackAtIndex(0) // Зацикливаем на первую песню
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0)),
                                        contentPadding = PaddingValues(0.dp),
                                        modifier = Modifier.weight(1f).height(52.dp)
                                    ) {
                                        Text("⏭ СЛЕД.", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
