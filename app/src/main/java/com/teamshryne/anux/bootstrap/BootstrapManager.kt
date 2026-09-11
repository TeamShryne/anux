package com.teamshryne.anux.bootstrap

import android.content.Context
import android.os.Build
import android.system.Os
import java.io.File

/**
 * Extracts the proot binary + runtime libs (libtalloc, libandroid-shmem)
 * from APK assets into files/ (exec-allowed, Termux-compatible layout).
 */
class BootstrapManager(private val context: Context) {
    data class Paths(
        val prootBin: File,
        val libDir: File,
        val prefix: File,
        val home: File,
        val tmp: File,
    )

    /** ABI asset dir, e.g. arm64-v8a. Throws when the device ABI is not shipped. */
    fun abiDirName(): String {
        val supported = Build.SUPPORTED_ABIS.toList()
        val shipped = setOf("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
        return supported.firstOrNull { it in shipped }
            ?: throw IllegalStateException("unsupported ABI: $supported")
    }

    /** Idempotent; must run off the main thread. */
    fun ensureInstalled(): Paths {
        val abi = abiDirName()
        val filesDir = context.filesDir
        val prefix = File(filesDir, "usr")
        val bin = File(prefix, "bin").apply { mkdirs() }
        val libDir = File(prefix, "lib").apply { mkdirs() }
        val home = File(filesDir, "home").apply { mkdirs() }
        val tmp = File(prefix, "tmp").apply { mkdirs() }

        copyAsset("proot/$abi/proot", File(bin, "proot"), executable = true)
        copyAsset("proot/$abi/lib/libtalloc.so.2", File(libDir, "libtalloc.so.2"), executable = false)
        copyAsset("proot/$abi/lib/libandroid-shmem.so", File(libDir, "libandroid-shmem.so"), executable = false)
        return Paths(File(bin, "proot"), libDir, prefix, home, tmp)
    }

    private fun copyAsset(assetPath: String, dest: File, executable: Boolean) {
        // Skip when the asset is unchanged (size check).
        try {
            val assetLen = context.assets.openFd(assetPath).use { it.length }
            if (dest.isFile && dest.length() == assetLen) {
                if (executable) makeExecutable(dest)
                return
            }
        } catch (_: Exception) {
            // openFd fails for compressed assets; fall through to copy.
        }
        dest.parentFile?.mkdirs()
        context.assets.open(assetPath).use { input ->
            dest.outputStream().use { out -> input.copyTo(out) }
        }
        if (executable) makeExecutable(dest)
    }

    private fun makeExecutable(file: File) {
        try {
            // Mirror TermuxInstaller exactly: owner-only 0700. Some OEM
            // kernels (Oplus) refuse exec of group/other-accessible files
            // from app-private dirs with EACCES and no audit trail.
            Os.chmod(file.absolutePath, 0b111_000_000)
        } catch (_: Exception) {
            file.setExecutable(true, true)
        }
    }
}
