package com.teamshryne.anux.data

import android.content.Context
import android.net.Uri
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.teamshryne.anux.distro.DistroBackup
import com.teamshryne.anux.distro.DistroCatalog
import com.teamshryne.anux.distro.DistroInstaller
import com.teamshryne.anux.distro.OciRef
import com.teamshryne.anux.worker.InstallWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Single access point for distro state: Room (registry) + files (truth for
 * installed rootfs) + WorkManager (installs) + ContentResolver (backup/restore).
 */
class DistroRepository(private val context: Context, private val db: AnuxDatabase) {
    fun containers(): Flow<List<ContainerEntity>> = db.containers().observe()

    fun catalog() = DistroCatalog.all

    fun isInstalled(alias: String): Boolean = OciRef.isInstalled(context.filesDir, alias)

    fun installProgress(alias: String): Flow<List<WorkInfo>> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWorkFlow(InstallWorker.nameFor(alias))

    /** Enqueue an OCI install; no-op when already installed or already queued. */
    fun enqueueInstall(imageRef: String, alias: String) {
        DistroInstaller.requireName(alias)
        if (isInstalled(alias)) return
        val req = OneTimeWorkRequestBuilder<InstallWorker>()
            .setInputData(workDataOf(InstallWorker.KEY_IMAGE to imageRef, InstallWorker.KEY_ALIAS to alias))
            .addTag("install")
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(InstallWorker.nameFor(alias), ExistingWorkPolicy.KEEP, req)
    }

    fun cancelInstall(alias: String) {
        WorkManager.getInstance(context).cancelUniqueWork(InstallWorker.nameFor(alias))
    }

    suspend fun remove(alias: String) = withContext(Dispatchers.IO) {
        cancelInstall(alias)
        runCatching { DistroInstaller(context.filesDir, context.cacheDir).uninstall(alias) }
        db.containers().delete(alias)
    }

    suspend fun backup(alias: String, uri: Uri) = withContext(Dispatchers.IO) {
        val tmp = File.createTempFile("anux-backup-", ".tar.gz", context.cacheDir)
        try {
            DistroBackup.backup(context.filesDir, alias, tmp, gzip = true)
            context.contentResolver.openOutputStream(uri)?.use { out ->
                tmp.inputStream().use { it.copyTo(out) }
            } ?: throw IllegalStateException("cannot open $uri for writing")
        } finally {
            tmp.delete()
        }
    }

    /** Restore from a content Uri. Returns the alias. */
    suspend fun restore(uri: Uri): String = withContext(Dispatchers.IO) {
        val tmp = File.createTempFile("anux-restore-", ".tar.gz", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { input.copyTo(it) }
            } ?: throw IllegalStateException("cannot open $uri for reading")
            val alias = DistroBackup.restore(context.filesDir, tmp)
            val manifest = File(context.filesDir, "containers/$alias/manifest.json")
            val (imageRef, canonical) = if (manifest.isFile) {
                val json = JSONObject(manifest.readText())
                json.optString("imageRef", alias) to json.optString("canonicalRef", "")
            } else {
                alias to ""
            }
            db.containers().upsert(
                ContainerEntity(alias, imageRef, canonical, "unknown", System.currentTimeMillis()),
            )
            alias
        } finally {
            tmp.delete()
        }
    }

    suspend fun imageCacheSize(): Long = withContext(Dispatchers.IO) {
        File(context.cacheDir, "oci_layers").walkTopDown()
            .filter { it.isFile }.sumOf { it.length() }
    }

    suspend fun clearImageCache() = withContext(Dispatchers.IO) {
        File(context.cacheDir, "oci_layers").deleteRecursively()
    }
}
