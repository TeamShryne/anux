package com.teamshryne.anux

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.teamshryne.anux.data.AnuxSettings
import com.teamshryne.anux.service.AnuxService
import com.teamshryne.anux.ui.DistrosScreen
import com.teamshryne.anux.ui.SettingsScreen
import com.teamshryne.anux.ui.TerminalScreen
import com.teamshryne.anux.ui.theme.AnuxTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val app = application as AnuxApp
            val settings by app.prefs.settings.collectAsStateWithLifecycle(
                initialValue = AnuxSettings(),
            )
            AnuxTheme(darkTheme = settings.themeMode != 2 &&
                (settings.themeMode == 1 || androidx.compose.foundation.isSystemInDarkTheme())) {
                AnuxRoot(app)
            }
        }
    }
}

private enum class Tab(val title: String) {
    Distros("Distros"),
    Terminal("Terminal"),
    Settings("Settings"),
}

@Composable
private fun AnuxRoot(app: AnuxApp) {
    val context = LocalContext.current
    var tab by remember { mutableStateOf(Tab.Distros) }
    var service by remember { mutableStateOf<AnuxService?>(null) }
    var pendingAlias by remember { mutableStateOf<String?>(null) }
    val settings by app.prefs.settings.collectAsStateWithLifecycle(initialValue = AnuxSettings())

    DisposableEffect(context) {
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = (binder as? AnuxService.LocalBinder)?.service
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                service = null
            }
        }
        val intent = Intent(context, AnuxService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
        context.bindService(intent, conn, Context.BIND_AUTO_CREATE)
        onDispose { context.unbindService(conn) }
    }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == Tab.Distros,
                    onClick = { tab = Tab.Distros },
                    icon = { Icon(Icons.Filled.Home, contentDescription = null) },
                    label = { Text("Distros") },
                )
                NavigationBarItem(
                    selected = tab == Tab.Terminal,
                    onClick = { tab = Tab.Terminal },
                    icon = { Icon(Icons.Filled.Star, contentDescription = null) },
                    label = { Text("Terminal") },
                )
                NavigationBarItem(
                    selected = tab == Tab.Settings,
                    onClick = { tab = Tab.Settings },
                    icon = { Icon(Icons.Filled.Settings, contentDescription = null) },
                    label = { Text("Settings") },
                )
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (tab) {
            Tab.Distros -> Box(modifier) {
                DistrosScreen(
                    repository = app.repository,
                    onLaunch = { alias ->
                        pendingAlias = alias
                        tab = Tab.Terminal
                    },
                )
            }
            Tab.Terminal -> Box(modifier) {
                TerminalScreen(
                    service = service,
                    pendingAlias = pendingAlias,
                    settings = settings,
                    onConsumePending = { pendingAlias = null },
                )
            }
            Tab.Settings -> Box(modifier) {
                SettingsScreen(
                    prefs = app.prefs,
                    repository = app.repository,
                    settings = settings,
                )
            }
        }
    }
}
