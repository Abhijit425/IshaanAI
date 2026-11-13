package com.ishaan.ai

import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.ishaan.ai.data.AppSettings
import com.ishaan.ai.ui.AppRoot
import com.ishaan.ai.ui.LockScreen
import com.ishaan.ai.ui.components.LocalContainer
import com.ishaan.ai.ui.theme.IshaanTheme
import kotlinx.coroutines.launch

class MainActivity : FragmentActivity() {
    private var locked by mutableStateOf(true)
    private var unlockedOnce by mutableStateOf(false)
    private var backgroundedAt = 0L
    private var container: AppContainer? = null

    private val processObserver = object : DefaultLifecycleObserver {
        override fun onStop(owner: LifecycleOwner) { backgroundedAt = SystemClock.elapsedRealtime() }
        override fun onStart(owner: LifecycleOwner) {
            val c = container ?: return
            if (backgroundedAt == 0L) return
            lifecycleScope.launch {
                val s = c.settings.current()
                if (s.pinSet && SystemClock.elapsedRealtime() - backgroundedAt > s.autoLockSeconds * 1000L) lock()
            }
        }
    }

    fun lock() { locked = true; container?.vault?.clearTemp() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        container = (application as IshaanApp).container()
        val c = container
        if (c == null) {
            setContent { IshaanTheme { StartupError((application as IshaanApp).startupError) } }
            return
        }
        c.vault.clearTemp()
        ProcessLifecycleOwner.get().lifecycle.addObserver(processObserver)
        lifecycleScope.launch {
            c.settings.settings.collect { s ->
                if (s.secureScreen) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        lifecycleScope.launch { runCatching { c.rescheduleReminders(); c.refreshAlerts() } }
        setContent {
            val settings by c.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings(pinSet = true))
            IshaanTheme(settings.darkMode, settings.largeText) {
                CompositionLocalProvider(LocalContainer provides c) {
                    // The app stays composed underneath so navigation and pending camera/file results survive auto-lock.
                    Box(Modifier.fillMaxSize()) {
                        if (unlockedOnce) AppRoot(onLock = { lock() })
                        if (locked) Surface(Modifier.fillMaxSize()) {
                            LockScreen(activity = this@MainActivity, settings = settings, onUnlocked = { locked = false; unlockedOnce = true })
                        }
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        ProcessLifecycleOwner.get().lifecycle.removeObserver(processObserver)
        super.onDestroy()
    }
}

@androidx.compose.runtime.Composable
private fun StartupError(t: Throwable?) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.padding(24.dp)) {
            Text("Ishaan AI could not open its secure storage.", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            Text("Your data has not been deleted. Please restart the phone and try again. If this keeps happening, restore from your latest encrypted backup after reinstalling.\n\nDetails: ${t?.message ?: t?.javaClass?.simpleName ?: "unknown"}")
        }
    }
}
