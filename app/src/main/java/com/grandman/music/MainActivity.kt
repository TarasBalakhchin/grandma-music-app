package com.grandman.music

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

class MainActivity : ComponentActivity() {

    private var incomingUrl by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIncomingIntent(intent)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    GrandmaMusicApp(
                        incomingUrl = incomingUrl,
                        onIncomingUrlHandled = { incomingUrl = null }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    // При возврате в приложение проверяем, нет ли в буфере новой ссылки из TikTok
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            checkClipboardForTikTok()
        }
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrBlank()) {
                val cleanUrl = TikTokParser.extractTikTokUrl(sharedText) ?: sharedText.trim()
                incomingUrl = cleanUrl
                // Очищаем action, чтобы при повороте экрана не сработало повторно
                intent.action = ""
            }
        }
    }

    private fun checkClipboardForTikTok() {
        try {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clipText = clipboard?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString() ?: return
            val tikTokUrl = TikTokParser.extractTikTokUrl(clipText) ?: return

            val prefs = getSharedPreferences("grandma_music_prefs", Context.MODE_PRIVATE)
            val lastClip = prefs.getString("last_auto_tiktok_url", null)

            // Срабатываем только один раз на каждую новую скопированную ссылку
            if (tikTokUrl != lastClip) {
                prefs.edit().putString("last_auto_tiktok_url", tikTokUrl).apply()
                incomingUrl = tikTokUrl
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
