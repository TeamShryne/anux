package com.teamshryne.anux.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.work.WorkInfo
import com.teamshryne.anux.data.ContainerEntity
import com.teamshryne.anux.data.DistroRepository
import com.teamshryne.anux.distro.DistroCatalog
import com.teamshryne.anux.distro.DistroManifest
import com.teamshryne.anux.distro.OciRef
import com.teamshryne.anux.worker.InstallWorker
import kotlinx.coroutines.launch

@Composable
fun DistrosScreen(repository: DistroRepository, onLaunch: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    val installed by repository.containers().collectAsStateWithLifecycle(initialValue = emptyList())
    val installedAliases = installed.map { it.alias }.toSet()
    var showCustom by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var backupAlias by remember { mutableStateOf<String?>(null) }

    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/gzip"),
    ) { uri: Uri? ->
        val alias = backupAlias ?: return@rememberLauncherForActivityResult
        if (uri != null) {
            scope.launch {
                try {
                    repository.backup(alias, uri)
                } catch (e: Exception) {
                    error = "Backup failed: ${e.message}"
                }
            }
        }
    }
    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                try {
                    val alias = repository.restore(uri)
                    onLaunch(alias)
                } catch (e: Exception) {
                    error = "Restore failed: ${e.message}"
                }
            }
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
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { showCustom = true }) { Text("Custom image") }
                OutlinedButton(onClick = { restoreLauncher.launch("*/*") }) { Text("Restore") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        items(DistroCatalog.all) { distro ->
            DistroCard(
                distro = distro,
                installed = installed.find { it.alias == distro.alias },
                isInstalled = distro.alias in installedAliases || repository.isInstalled(distro.alias),
                onInstall = { repository.enqueueInstall(distro.imageRef, distro.alias) },
                onLaunch = { onLaunch(distro.alias) },
                onDelete = { scope.launch { repository.remove(distro.alias) } },
                onBackup = {
                    backupAlias = distro.alias
                    backupLauncher.launch("${distro.alias}.tar.gz")
                },
                progressOf = { repository.installProgress(distro.alias) },
            )
        }
    }

    if (showCustom) {
        var imageRef by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCustom = false },
            title = { Text("Install custom OCI image") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Any Docker/OCI ref, e.g. ubuntu:24.04 or ghcr.io/user/img:tag")
                    OutlinedTextField(
                        value = imageRef,
                        onValueChange = { imageRef = it },
                        label = { Text("Image ref") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val ref = imageRef.trim()
                    if (ref.isNotEmpty()) {
                        val alias = OciRef.localName(ref)
                        repository.enqueueInstall(ref, alias)
                        showCustom = false
                        error = null
                    }
                }) { Text("Install") }
            },
            dismissButton = { TextButton(onClick = { showCustom = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun DistroCard(
    distro: DistroManifest,
    installed: ContainerEntity?,
    isInstalled: Boolean,
    onInstall: () -> Unit,
    onLaunch: () -> Unit,
    onDelete: () -> Unit,
    onBackup: () -> Unit,
    progressOf: () -> kotlinx.coroutines.flow.Flow<List<WorkInfo>>,
) {
    val work by progressOf().collectAsStateWithLifecycle(initialValue = emptyList())
    val running = work.firstOrNull { !it.state.isFinished }
    val failed = work.firstOrNull { it.state == WorkInfo.State.FAILED }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(distro.displayName, style = MaterialTheme.typography.titleLarge)
            Text(distro.imageRef, style = MaterialTheme.typography.bodySmall)
            if (installed != null) {
                Text(installed.canonicalRef, style = MaterialTheme.typography.bodySmall)
            }
            Text(distro.description, style = MaterialTheme.typography.bodyMedium)

            if (running != null) {
                val p = running.progress
                val stage = p.getString(InstallWorker.KEY_STAGE) ?: "working"
                val done = p.getLong(InstallWorker.KEY_DONE, 0)
                val total = p.getLong(InstallWorker.KEY_TOTAL, -1)
                Text("$stage…", style = MaterialTheme.typography.bodySmall)
                if (total > 0) {
                    LinearProgressIndicator(
                        progress = { (done.toFloat() / total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            }
            failed?.let {
                val msg = it.outputData.getString(InstallWorker.KEY_ERROR) ?: "install failed"
                Text(msg, color = MaterialTheme.colorScheme.error)
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    isInstalled -> {
                        Button(onClick = onLaunch) { Text("Launch") }
                        OutlinedButton(onClick = onBackup) { Text("Backup") }
                        OutlinedButton(onClick = onDelete) { Text("Delete") }
                    }
                    running != null -> {
                        OutlinedButton(onClick = {}) { Text("Installing…") }
                    }
                    else -> {
                        Button(onClick = onInstall) { Text("Install") }
                    }
                }
            }
            if (failed != null && !isInstalled) {
                OutlinedButton(onClick = onInstall, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Retry")
                }
            }
        }
    }
}
