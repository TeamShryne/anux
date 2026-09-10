package com.teamshryne.anux.distro

import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock

/**
 * Inter-process container lock (mirrors proot-distro's ContainerLock).
 * One lock file per container; exclusive for install/remove, shared for login.
 */
object ContainerLock {
    fun <T> withLock(filesDir: File, alias: String, exclusive: Boolean, block: () -> T): T {
        val lockFile = File(filesDir, "containers/$alias.lock")
        lockFile.parentFile?.mkdirs()
        RandomAccessFile(lockFile, "rw").use { raf ->
            val channel: FileChannel = raf.channel
            val lock: FileLock = if (exclusive) channel.lock() else channel.lock(0L, Long.MAX_VALUE, true)
            try {
                return block()
            } finally {
                try {
                    lock.release()
                } catch (_: Exception) {
                }
            }
        }
    }
}
