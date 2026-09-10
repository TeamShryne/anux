package com.teamshryne.anux.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.teamshryne.anux.distro.DistroCatalog
import com.teamshryne.anux.distro.OciRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun DistrosScreen(onLaunch: (String) -> Unit) {
    val context = LocalContext.current
    var installed by remember { mutableStateOf(setOf<String>()) }

    LaunchedEffect(Unit) {
        installed = withContext(Dispatchers.IO) {
            val filesDir = context.filesDir
            DistroCatalog.all.filter { OciRef.isInstalled(filesDir, it.alias) }
                .map { it.alias }.toSet()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Distros", style = MaterialTheme.typography.headlineMedium)
            Text(
                "No root needed. Alpine is the fastest first boot.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        items(DistroCatalog.all) { distro ->
            val isInstalled = distro.alias in installed
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(distro.displayName, style = MaterialTheme.typography.titleLarge)
                    Text(distro.imageRef, style = MaterialTheme.typography.bodySmall)
                    Text(distro.description, style = MaterialTheme.typography.bodyMedium)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (isInstalled) {
                            Button(onClick = { onLaunch(distro.alias) }) { Text("Launch") }
                        } else {
                            // Install lands with the OCI puller (WorkManager); disabled until then.
                            OutlinedButton(onClick = {}, enabled = false) { Text("Install soon") }
                        }
                    }
                    if (isInstalled) {
                        Text(
                            "Installed",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
        }
    }
}
