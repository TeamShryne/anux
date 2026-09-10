package com.teamshryne.anux.worker

import android.content.Context
import android.os.Process
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.teamshryne.anux.data.AnuxDatabase
import com.teamshryne.anux.data.ContainerEntity
import com.teamshryne.anux.distro.CpuArch
import com.teamshryne.anux.distro.DistroInstaller
import com.teamshryne.anux.distro.DockerRegistry

/**
 * OCI install in the background with progress (stage, downloaded, total).
 * Unique work name: install-<alias>.
 */
class InstallWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val imageRef = inputData.getString(KEY_IMAGE).orEmpty()
        val alias = inputData.getString(KEY_ALIAS).orEmpty()
        if (imageRef.isEmpty() || alias.isEmpty()) return Result.failure()
        return try {
            val installer = DistroInstaller(
                filesDir = applicationContext.filesDir,
                cacheDir = applicationContext.cacheDir,
                registry = DockerRegistry(),
                uid = Process.myUid(),
                gid = Process.myGid(),
            )
            val info = installer.install(
                imageRef = imageRef,
                alias = alias,
                arch = CpuArch.AARCH64,
            ) { p ->
                setProgress(
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
        } catch (e: Exception) {
            if (runAttemptCount >= 2) {
                Result.failure(workDataOf(KEY_ERROR to (e.message ?: "install failed")))
            } else {
                Result.retry()
            }
        }
    }

    companion object {
        const val KEY_IMAGE = "imageRef"
        const val KEY_ALIAS = "alias"
        const val KEY_STAGE = "stage"
        const val KEY_INDEX = "index"
        const val KEY_COUNT = "count"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_ERROR = "error"

        fun nameFor(alias: String) = "install-$alias"
    }
}
