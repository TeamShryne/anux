package com.teamshryne.anux.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Process
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.teamshryne.anux.data.AnuxDatabase
import com.teamshryne.anux.data.ContainerEntity
import com.teamshryne.anux.distro.CpuArch
import com.teamshryne.anux.distro.DigestMismatchException
import com.teamshryne.anux.distro.DistroInstaller
import com.teamshryne.anux.distro.DockerRegistry
import com.teamshryne.anux.distro.RegistryException

/**
 * OCI install in the background with progress (stage, downloaded, total).
 * Unique work name: install-<alias>.
 */
class InstallWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    /**
     * Expedited work requires foreground info; the notification also keeps
     * Oplus-style battery optimizers from killing the process mid-extract
     * (which used to leave a partial rootfs behind).
     */
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Installs", NotificationManager.IMPORTANCE_LOW),
        )
        val notif = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Installing ${inputData.getString(KEY_ALIAS).orEmpty()}…")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            @Suppress("DEPRECATION")
            ForegroundInfo(NOTIF_ID, notif)
        }
    }

    override suspend fun doWork(): Result {
        val imageRef = inputData.getString(KEY_IMAGE).orEmpty()
        val alias = inputData.getString(KEY_ALIAS).orEmpty()
        if (imageRef.isEmpty() || alias.isEmpty()) {
            return Result.failure(workDataOf(KEY_ERROR to "missing imageRef/alias input"))
        }
        return try {
            val uid = Process.myUid()
            val installer = DistroInstaller(
                filesDir = applicationContext.filesDir,
                cacheDir = applicationContext.cacheDir,
                registry = DockerRegistry(),
                uid = uid,
                gid = uid,
            )
            val info = installer.install(
                imageRef = imageRef,
                alias = alias,
                arch = deviceCpuArch(),
            ) { p ->
                @Suppress("DEPRECATION")
                setProgressAsync(
                    workDataOf(
                        KEY_STAGE to p.stage,
                        KEY_INDEX to p.layerIndex,
                        KEY_COUNT to p.layerCount,
                        KEY_DONE to p.downloadedBytes,
                        KEY_TOTAL to p.totalBytes,
                    ),
                )
            }
            AnuxDatabase.get(applicationContext).containers().upsert(
                ContainerEntity(
                    alias = info.alias,
                    imageRef = info.imageRef,
                    canonicalRef = info.canonicalRef,
                    arch = info.arch.name,
                    createdAt = System.currentTimeMillis(),
                ),
            )
            Result.success()
        } catch (e: Throwable) {
            Log.e(TAG, "install $alias ($imageRef) failed", e)
            // Deterministic failures must not retry: they would keep the card
            // stuck on "working…" through 3 identical attempts before surfacing
            // the real error (e.g. "no arm64 image in index").
            // Always include the exception type: a bare null message would
            // otherwise render as a useless generic "install failed".
            val detail = e.message?.takeIf { it.isNotBlank() }
                ?: e.toString()
            val cause = generateSequence(e.cause) { it.cause }.firstOrNull()
                ?.let { " (caused by ${it.javaClass.simpleName}: ${it.message})" }
                .orEmpty()
            if (isDeterministicFailure(e) || runAttemptCount >= 2) {
                Result.failure(workDataOf(KEY_ERROR to "$detail$cause"))
            } else {
                Result.retry()
            }
        }
    }

    companion object {
        const val TAG = "InstallWorker"
        const val CHANNEL_ID = "anux-install"
        const val NOTIF_ID = 1338
        const val KEY_IMAGE = "imageRef"
        const val KEY_ALIAS = "alias"
        const val KEY_STAGE = "stage"
        const val KEY_INDEX = "index"
        const val KEY_COUNT = "count"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_ERROR = "error"

        fun nameFor(alias: String) = "install-$alias"

        /** Map the device's primary ABI to an OCI arch (Termux proot-distro mapping). */
        fun deviceCpuArch(): CpuArch {
            val abis = runCatching { Build.SUPPORTED_ABIS.toList() }.getOrDefault(emptyList())
            val primary = abis.firstOrNull().orEmpty().lowercase()
            return when {
                primary.startsWith("arm64") || primary.startsWith("aarch64") -> CpuArch.AARCH64
                primary.startsWith("armeabi") || primary.startsWith("armv7") -> CpuArch.ARM
                primary.startsWith("x86_64") -> CpuArch.X86_64
                primary.startsWith("x86") -> CpuArch.I686
                primary.startsWith("riscv64") -> CpuArch.RISCV64
                else -> CpuArch.AARCH64
            }
        }

        /** Errors that will fail identically on retry: surface immediately. */
        fun isDeterministicFailure(e: Throwable): Boolean = when (e) {
            is IllegalStateException, is IllegalArgumentException,
            is DigestMismatchException, is SecurityException -> true
            is RegistryException -> {
                val msg = e.message.orEmpty()
                // "no arm64 image in index", "unsupported digest", auth misconfig, etc.
                "no " in msg && "image in index" in msg ||
                    "unsupported digest" in msg ||
                    "image has no layers" in msg ||
                    "without Bearer challenge" in msg
            }
            else -> false
        }
    }
}
