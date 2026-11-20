package com.grandman.music

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private fun formatTime(ms: Int): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}

@Composable
fun GrandmaMusicApp(
    incomingUrl: String? = null,
    onIncomingUrlHandled: () -> Unit = {}
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var currentTab by remember { mutableIntStateOf(0) }

    var searchQuery by remember { mutableStateOf("") }
    var tracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    val downloadingUrls = remember { mutableStateListOf<String>() }

    var localTracks by remember { mutableStateOf<List<LocalTrack>>(emptyList()) }

    val existingTrackTitles = remember(localTracks) {
        localTracks.map { it.title.lowercase() }.toSet()
    }

    val audioPlayer = remember { AudioPreviewPlayer() }
    var currentlyPlayingSource by remember { mutableStateOf<String?>(null) }
    var isBufferingSource by remember { mutableStateOf<String?>(null) }

    var currentLocalIndex by remember { mutableIntStateOf(-1) }
    var isPaused by remember { mutableStateOf(false) }
    var currentPositionMs by remember { mutableIntStateOf(0) }
    var totalDurationMs by remember { mutableIntStateOf(0) }
    var isUserDraggingSlider by remember { mutableStateOf(false) }
    var sliderDragValue by remember { mutableFloatStateOf(0f) }

    var draggedItemIndex by remember { mutableIntStateOf(-1) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    val itemHeightPx = with(LocalDensity.current) { 130.dp.toPx() }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ ->
        localTracks = MusicDownloader.getDownloadedTracks(context)
    }

    LaunchedEffect(Unit) {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(context, perm) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(perm)
        } else {
            localTracks = MusicDownloader.getDownloadedTracks(context)
        }
    }

    DisposableEffect(Unit) {
        onDispose { audioPlayer.stop() }
    }

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

    // Универсальный поиск: сам определяет, это ссылка TikTok или обычный запрос песни
    fun performUniversalSearch(rawQuery: String) {
        val trimmed = rawQuery.trim()
        if (trimmed.isEmpty()) return

        stopAllPlayback()
        isLoading = true

        scope.launch {
            localTracks = MusicDownloader.getDownloadedTracks(context)

            if (TikTokParser.isTikTokUrl(trimmed)) {
                val tikTokTrack = TikTokParser.fetchAudioFromTikTok(trimmed)
                if (tikTokTrack != null) {
                    tracks = listOf(tikTokTrack)
                } else {
                    tracks = emptyList()
                    Toast.makeText(context, "Не удалось получить звук из TikTok. Проверьте ссылку.", Toast.LENGTH_LONG).show()
                }
            } else {
                tracks = MusicParser.searchTracks(trimmed)
            }

            isLoading = false
        }
    }

    // Автоматически запускаем поиск, если ссылку передали из TikTok через «Поделиться» или буфер обмена
    LaunchedEffect(incomingUrl) {
        if (!incomingUrl.isNullOrBlank()) {
            currentTab = 0
            searchQuery = incomingUrl
            performUniversalSearch(incomingUrl)
            onIncomingUrlHandled()
        }
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
                val activeIdx = localTracks.indexOfFirst { it.filePath == currentlyPlayingSource }
                val nextIdx = if (activeIdx != -1) activeIdx + 1 else index + 1
                if (nextIdx < localTracks.size) {
                    playLocalTrackAtIndex(nextIdx)
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

    fun moveTrackInQueue(fromIndex: Int, toIndex: Int) {
        if (fromIndex !in localTracks.indices || toIndex !in localTracks.indices || fromIndex == toIndex) return
        val mutable = localTracks.toMutableList()
        val item = mutable.removeAt(fromIndex)
        mutable.add(toIndex, item)
        localTracks = mutable
        MusicDownloader.saveTrackOrder(context, mutable)

        if (currentlyPlayingSource != null) {
            currentLocalIndex = localTracks.indexOfFirst { it.filePath == currentlyPlayingSource }
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // ВЕРХНИЕ ВКЛАДКИ
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = {
                    stopAllPlayback()
                    localTracks = MusicDownloader.getDownloadedTracks(context)
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
                Text("🎵 МОИ ПЕСНИ (${localTracks.size})", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = if (currentTab == 1) Color.White else Color.Black)
            }
        }

        // ВКЛАДКА 0: ПОИСК ПЕСЕН И ЗАГРУЗКА ИЗ TIKTOK
        if (currentTab == 0) {
            val voiceLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.StartActivityForResult()
            ) { result ->
                if (result.resultCode == Activity.RESULT_OK) {
                    val spokenText = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
                    if (!spokenText.isNullOrBlank()) {
                        searchQuery = spokenText
                        performUniversalSearch(spokenText)
                    }
                }
            }

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                label = { Text("Песня, артист или ссылка TikTok", fontSize = 17.sp) },
                textStyle = LocalTextStyle.current.copy(fontSize = 20.sp),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(10.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { performUniversalSearch(searchQuery) },
                    modifier = Modifier.weight(1f).height(58.dp)
                ) {
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
                    modifier = Modifier.weight(1f).height(58.dp)
                ) {
                    Text("🎤 ГОЛОСОМ", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Кнопка быстрой вставки ссылки из буфера обмена (удобно для TikTok)
            OutlinedButton(
                onClick = {
                    val clipText = clipboardManager.getText()?.text?.trim()
                    if (!clipText.isNullOrBlank()) {
                        val cleanUrl = TikTokParser.extractTikTokUrl(clipText) ?: clipText
                        searchQuery = cleanUrl
                        performUniversalSearch(cleanUrl)
                    } else {
                        Toast.makeText(context, "В буфере обмена пока пусто", Toast.LENGTH_SHORT).show()
                    }
                },
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) {
                Text("📋 ВСТАВИТЬ ССЫЛКУ ИЗ БУФЕРА", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Color(0xFF00695C))
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(modifier = Modifier.size(64.dp))
                        Spacer(modifier = Modifier.height(16.dp))
                        Text("Загружаем, подождите...", fontSize = 22.sp)
                    }
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxSize()) {
                    items(tracks) { track ->
                        val expectedTitle = MusicDownloader.getSafeTrackTitle(track).lowercase()
                        val isDownloading = downloadingUrls.contains(track.downloadUrl)
                        val isAlreadyInFolder = existingTrackTitles.contains(expectedTitle)
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
                                                    Toast.makeText(context, "Не удалось включить звук", Toast.LENGTH_SHORT).show()
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
                                                localTracks = MusicDownloader.getDownloadedTracks(context)
                                                Toast.makeText(context, "Сохранено в «Мои песни»!", Toast.LENGTH_LONG).show()
                                            } else {
                                                Toast.makeText(context, "Не удалось скачать этот файл.", Toast.LENGTH_LONG).show()
                                            }
                                        }
                                    },
                                    enabled = !isAlreadyInFolder && !isDownloading,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF2E7D32),
                                        disabledContainerColor = if (isDownloading) Color(0xFFFFB74D) else Color(0xFFA5D6A7)
                                    ),
                                    modifier = Modifier.fillMaxWidth().height(56.dp)
                                ) {
                                    Text(
                                        text = when {
                                            isDownloading -> "⏳ СКАЧИВАЕМ... ЖДИТЕ"
                                            isAlreadyInFolder -> "✓ УЖЕ В «МОИХ ПЕСНЯХ»"
                                            else -> "⬇ СКАЧАТЬ В ТЕЛЕФОН"
                                        },
                                        fontSize = 19.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isAlreadyInFolder || isDownloading) Color.Black else Color.White
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // ВКЛАДКА 1: МОИ ПЕСНИ (ОЧЕРЕДЬ, ПЕРЕТАСКИВАНИЕ, СЛУЧАЙНО, ПЛЕЕР)
        if (currentTab == 1) {
            if (localTracks.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("В папке GranyMusic пока нет песен.\nПерейдите в «ПОИСК» сверху.", fontSize = 22.sp, fontWeight = FontWeight.Medium)
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    Button(
                        onClick = {
                            val shuffled = localTracks.shuffled()
                            localTracks = shuffled
                            MusicDownloader.saveTrackOrder(context, shuffled)
                            playLocalTrackAtIndex(0)
                            Toast.makeText(context, "Песни перемешаны!", Toast.LENGTH_SHORT).show()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF7B1FA2)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                    ) {
                        Text("🔀 ВКЛЮЧИТЬ СЛУЧАЙНО (ПЕРЕМЕШАТЬ)", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }

                    Text(
                        text = "Удерживайте значок ☰ или жмите ⬆⬇, чтобы менять очередь:",
                        fontSize = 15.sp,
                        color = Color.DarkGray,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )

                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    ) {
                        itemsIndexed(localTracks, key = { _, item -> item.filePath }) { index, localTrack ->
                            val isThisTrackActive = currentlyPlayingSource == localTrack.filePath
                            val isBeingDragged = draggedItemIndex == index

                            Card(
                                elevation = CardDefaults.cardElevation(defaultElevation = if (isBeingDragged) 12.dp else 4.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = if (isBeingDragged) Color(0xFFFFF9C4) else MaterialTheme.colorScheme.surface
                                ),
                                border = if (isThisTrackActive) BorderStroke(3.dp, Color(0xFF2E7D32)) else null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .zIndex(if (isBeingDragged) 1f else 0f)
                                    .graphicsLayer {
                                        translationY = if (isBeingDragged) dragOffsetY else 0f
                                    }
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier
                                                .size(48.dp)
                                                .pointerInput(localTracks.size) {
                                                    detectDragGestures(
                                                        onDragStart = {
                                                            draggedItemIndex = index
                                                            dragOffsetY = 0f
                                                        },
                                                        onDragEnd = {
                                                            draggedItemIndex = -1
                                                            dragOffsetY = 0f
                                                        },
                                                        onDragCancel = {
                                                            draggedItemIndex = -1
                                                            dragOffsetY = 0f
                                                        },
                                                        onDrag = { change, dragAmount ->
                                                            change.consume()
                                                            dragOffsetY += dragAmount.y
                                                            val currentIdx = draggedItemIndex
                                                            if (currentIdx != -1) {
                                                                if (dragOffsetY > itemHeightPx && currentIdx < localTracks.size - 1) {
                                                                    moveTrackInQueue(currentIdx, currentIdx + 1)
                                                                    draggedItemIndex = currentIdx + 1
                                                                    dragOffsetY -= itemHeightPx
                                                                } else if (dragOffsetY < -itemHeightPx && currentIdx > 0) {
                                                                    moveTrackInQueue(currentIdx, currentIdx - 1)
                                                                    draggedItemIndex = currentIdx - 1
                                                                    dragOffsetY += itemHeightPx
                                                                }
                                                            }
                                                        }
                                                    )
                                                }
                                        ) {
                                            Text("☰", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1565C0))
                                        }

                                        Text(
                                            text = "${index + 1}. ${localTrack.title}",
                                            fontSize = 20.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier
                                                .weight(1f)
                                                .padding(horizontal = 8.dp)
                                        )

                                        Column {
                                            OutlinedButton(
                                                onClick = { moveTrackInQueue(index, index - 1) },
                                                enabled = index > 0,
                                                contentPadding = PaddingValues(0.dp),
                                                modifier = Modifier.size(width = 44.dp, height = 36.dp)
                                            ) {
                                                Text("⬆", fontSize = 18.sp)
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))
                                            OutlinedButton(
                                                onClick = { moveTrackInQueue(index, index + 1) },
                                                enabled = index < localTracks.size - 1,
                                                contentPadding = PaddingValues(0.dp),
                                                modifier = Modifier.size(width = 44.dp, height = 36.dp)
                                            ) {
                                                Text("⬇", fontSize = 18.sp)
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(10.dp))

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
                                            modifier = Modifier.weight(1f).height(52.dp)
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
                                            modifier = Modifier.height(52.dp)
                                        ) {
                                            Text("🗑", fontSize = 20.sp, color = Color(0xFFC62828))
                                        }
                                    }
                                }
                            }
                        }
                    }

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
                                    text = "Сейчас играет (${currentLocalIndex + 1}/${localTracks.size}): ${activeTrack.title}",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )

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

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(formatTime(sliderVal.toInt()), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                    Text(formatTime(totalDurationMs), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                }

                                Spacer(modifier = Modifier.height(8.dp))

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
                                                playLocalTrackAtIndex(0)
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
