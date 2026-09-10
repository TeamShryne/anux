package com.teamshryne.anux.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.teamshryne.anux.data.AnuxPrefs
import com.teamshryne.anux.data.AnuxSettings
import com.teamshryne.anux.data.DistroRepository
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(prefs: AnuxPrefs, repository: DistroRepository, settings: AnuxSettings) {
    val scope = rememberCoroutineScope()
    var cacheSize by remember { mutableStateOf<Long?>(null) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Settings", style = MaterialTheme.typography.headlineMedium)

        Text("Appearance", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val labels = listOf("System", "Dark", "Light")
            labels.forEachIndexed { i, label ->
                OutlinedButton(
                    onClick = { scope.launch { prefs.update { it.copy(themeMode = i) } } },
                    enabled = settings.themeMode != i,
                ) { Text(label) }
            }
        }
        Text("Terminal font size: ${settings.fontSize}")
        Slider(
            value = settings.fontSize.toFloat(),
            onValueChangeFinished = {},
            onValueChange = { v ->
                scope.launch { prefs.update { it.copy(fontSize = v.toInt().coerceIn(8, 32)) } }
            },
            valueRange = 8f..32f,
            steps = 23,
        )

        Text("Runtime", style = MaterialTheme.typography.titleMedium)
        var dns1 by remember(settings.dnsPrimary) { mutableStateOf(settings.dnsPrimary) }
        var dns2 by remember(settings.dnsSecondary) { mutableStateOf(settings.dnsSecondary) }
        var kernel by remember(settings.kernelRelease) { mutableStateOf(settings.kernelRelease) }
        var hostname by remember(settings.hostname) { mutableStateOf(settings.hostname) }
        OutlinedTextField(dns1, { dns1 = it }, label = { Text("Primary DNS") }, singleLine = true)
        OutlinedTextField(dns2, { dns2 = it }, label = { Text("Secondary DNS") }, singleLine = true)
        OutlinedTextField(kernel, { kernel = it }, label = { Text("Kernel release spoof") }, singleLine = true)
        OutlinedTextField(hostname, { hostname = it }, label = { Text("Hostname") }, singleLine = true)
        Button(onClick = {
            scope.launch {
                prefs.update {
                    it.copy(
                        dnsPrimary = dns1.ifBlank { "8.8.8.8" },
                        dnsSecondary = dns2.ifBlank { "8.8.4.4" },
                        kernelRelease = kernel.ifBlank { "6.17.0-PRoot-Distro" },
                        hostname = hostname.ifBlank { "localhost" },
                    )
                }
            }
        }) { Text("Save runtime") }

        Text("Storage", style = MaterialTheme.typography.titleMedium)
        Text(
            cacheSize?.let { "Image layer cache: ${it / 1024 / 1024} MB" } ?: "Image layer cache: …",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                scope.launch { cacheSize = repository.imageCacheSize() }
            }) { Text("Refresh") }
            OutlinedButton(onClick = {
                scope.launch {
                    repository.clearImageCache()
                    cacheSize = 0L
                }
            }) { Text("Clear cache") }
        }
    }
}
