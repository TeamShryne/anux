package com.teamshryne.anux.distro

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Applies OCI image layers (tarballs) onto a rootfs directory.
 * Handles whiteouts (.wh.*), opaque dirs (.wh..wh..opq), top-level strip
 * detection, and skips device nodes — mirroring proot-distro's apply_layer.
 */
object LayerExtractor {
    val ROOTFS_DIRS = setOf(
        "bin", "boot", "dev", "etc", "home", "lib", "lib64", "media", "mnt",
        "opt", "proc", "root", "run", "sbin", "srv", "sys", "tmp", "usr", "var",
    )

    /** Score strip counts 0..4 against known rootfs top-level dirs. */
    fun detectStripCount(entryNames: Collection<String>): Int {
        var best = 0
        var bestScore = -1
        for (strip in 0..4) {
            var score = 0
            for (name in entryNames) {
                val parts = name.split("/").filter { it.isNotEmpty() }
                if (parts.size > strip && parts[strip] in ROOTFS_DIRS) score++
            }
            if (score > bestScore) {
                bestScore = score
                best = strip
            }
        }
        return best
    }

    fun applyLayer(layerFile: File, rootfs: File) {
        rootfs.mkdirs()
        // Pass 1: collect (stripped) paths for strip detection + opaque handling.
        val rawNames = mutableListOf<String>()
        openTar(layerFile).use { tin ->
            var e = tin.nextEntry
            while (e != null) {
                rawNames.add(e.name)
                e = tin.nextEntry
            }
        }
        val strip = detectStripCount(rawNames)
        val layerPaths: Set<String> = rawNames
            .mapNotNull { stripName(it, strip) }
            .toSet()

        // Opaque dirs: clear pre-existing content not re-added by this layer.
        for (raw in rawNames) {
            val stripped = stripName(raw, strip) ?: continue
            if (stripped.endsWith("/.wh..wh..opq") || stripped == ".wh..wh..opq") {
                val dirRel = stripped.substringBeforeLast("/").takeIf { it != stripped } ?: ""
                clearOpaqueDir(rootfs, dirRel, layerPaths)
            }
        }

        // Pass 2: extract in order, applying whiteouts.
        openTar(layerFile).use { tin ->
            var entry = tin.nextEntry
            while (entry != null) {
                val rel = stripName(entry.name, strip)
                if (rel != null && rel.isNotEmpty()) {
                    applyEntry(tin, entry, rootfs, rel)
                }
                entry = tin.nextEntry
            }
        }
    }

    // -- internals --------------------------------------------------------

    internal fun stripName(name: String, strip: Int): String? {
        val parts = name.split("/").filter { it.isNotEmpty() }
        if (parts.size <= strip) return null
        return parts.drop(strip).joinToString("/")
    }

    private fun applyEntry(
        tin: TarArchiveInputStream,
        entry: org.apache.commons.compress.archivers.tar.TarArchiveEntry,
        rootfs: File,
        rel: String,
    ) {
        val base = rel.substringAfterLast("/")
        if (base.startsWith(".wh.")) {
            if (base == ".wh..wh..opq") return // handled in pre-pass
            val targetRel = rel.substringBeforeLast("/") + "/" + base.removePrefix(".wh.")
            val target = sanitize(rootfs, targetRel) ?: return
            deleteTree(target.toFile())
            return
        }
        val dest = sanitize(rootfs, rel) ?: return
        when {
            entry.isDirectory -> {
                dest.toFile().mkdirs()
                applyMode(dest.toFile(), entry.mode)
            }
            entry.isSymbolicLink -> {
                // Dangling links are legal (target often appears in a later layer/
                // entry); NIO creates them fine. Delete-then-create with one retry
                // so a pre-existing dir/file never blocks the link.
                deleteTree(dest.toFile())
                dest.parent?.toFile()?.mkdirs()
                val linkName = entry.linkName
                var created = runCatching {
                    Files.createSymbolicLink(dest, Path.of(linkName))
                    true
                }.getOrDefault(false)
                if (!created) {
                    deleteTree(dest.toFile())
                    created = runCatching {
                        Files.createSymbolicLink(dest, Path.of(linkName))
                        true
                    }.getOrDefault(false)
                }
                if (!created && linkName.isEmpty()) {
                    // Empty link target: nothing sane to create; skip.
                }
            }
            entry.isLink -> {
                deleteTree(dest.toFile())
                dest.parent?.toFile()?.mkdirs()
                // Hardlink targets: absolute targets resolve under rootfs;
                // relative targets resolve against the entry's own directory
                // (tar semantics), then must still land inside rootfs.
                val rawTarget = entry.linkName
                val linkTarget: Path = if (rawTarget.startsWith("/")) {
                    sanitize(rootfs, rawTarget.trimStart('/'))
                } else {
                    val siblingRel = if (rel.contains("/")) {
                        rel.substringBeforeLast("/") + "/" + rawTarget
                    } else {
                        rawTarget
                    }
                    sanitize(rootfs, siblingRel)
                } ?: return
                val linked = runCatching {
                    Files.createLink(dest, linkTarget)
                    true
                }.getOrDefault(false)
                if (!linked) {
                    // Cross-device or missing target: fall back to a file copy.
                    runCatching {
                        if (linkTarget.toFile().isFile) {
                            dest.parent?.toFile()?.mkdirs()
                            Files.copy(linkTarget, dest, StandardCopyOption.REPLACE_EXISTING)
                        }
                    }
                }
            }
            entry.isFile || !entry.isBlockDevice && !entry.isCharacterDevice && !entry.isFIFO -> {
                dest.parent?.toFile()?.mkdirs()
                // If a dir/symlink sits where the file goes, remove it first.
                val f = dest.toFile()
                if (f.isDirectory && dest?.let { Files.isSymbolicLink(it) } != true) deleteTree(f)
                else if (dest?.let { Files.isSymbolicLink(it) } == true || f.isFile) f.delete()
                Files.copy(tin, dest, StandardCopyOption.REPLACE_EXISTING)
                applyMode(dest.toFile(), entry.mode)
            }
            else -> { /* skip device nodes, fifos, sockets */ }
        }
    }

    /** Apply tar mode bits: any exec bit -> owner+group/other exec; preserve rw. */
    private fun applyMode(file: File, mode: Int) {
        try {
            if (mode and 0b001_001_001 != 0) file.setExecutable(true, false)
            if (mode and 0b100_100_100 != 0) file.setReadable(true, false)
            if (mode and 0b010_010_010 != 0) file.setWritable(true, true)
        } catch (_: Exception) {
        }
    }

    private fun clearOpaqueDir(rootfs: File, dirRel: String, layerPaths: Set<String>) {
        val dir = if (dirRel.isEmpty()) rootfs else sanitize(rootfs, dirRel)?.toFile() ?: return
        if (!dir.isDirectory) return
        val prefix = if (dirRel.isEmpty()) "" else "$dirRel/"
        dir.walkTopDown().filter { it != dir }.sortedByDescending { it.path.length }.forEach { f ->
            val rel = f.relativeTo(rootfs).invariantSeparatorsPath
            if (!rel.startsWith(prefix)) return@forEach
            val sub = rel.removePrefix(prefix)
            // Keep anything the layer itself provides (file or ancestor dir of one).
            val kept = layerPaths.any { it == sub || it.startsWith("$sub/") || sub.startsWith(it) }
            if (!kept) deleteTree(f)
        }
    }

    private fun deleteTree(f: File) {
        if (!f.exists() && !Files.isSymbolicLink(f.toPath())) return
        if (Files.isSymbolicLink(f.toPath()) || f.isFile) {
            f.delete()
            return
        }
        f.walkBottomUp().forEach { runCatching { Files.deleteIfExists(it.toPath()) } }
    }

    /** Resolve [rel] under [rootfs]; null if it escapes (absolute or ..). */
    internal fun sanitize(rootfs: File, rel: String): Path? {
        val root = rootfs.canonicalFile.toPath().normalize()
        val dest = root.resolve(rel).normalize()
        if (!dest.startsWith(root)) return null
        return dest
    }

    internal fun openTar(layerFile: File): TarArchiveInputStream {
        val raw = BufferedInputStream(layerFile.inputStream(), 64 * 1024)
        raw.mark(6)
        val magic = ByteArray(6)
        val n = raw.read(magic)
        raw.reset()
        val stream: InputStream = when {
            n >= 2 && magic[0] == 0x1f.toByte() && magic[1] == 0x8b.toByte() ->
                GzipCompressorInputStream(raw)
            n >= 6 && magic[0] == 0xfd.toByte() && magic[1] == 0x37.toByte() &&
                magic[2] == 0x7a.toByte() && magic[3] == 0x58.toByte() &&
                magic[4] == 0x5a.toByte() && magic[5] == 0x00.toByte() ->
                XZCompressorInputStream(raw)
            n >= 4 && magic[0] == 0x28.toByte() && magic[1] == 0xb5.toByte() &&
                magic[2] == 0x2f.toByte() && magic[3] == 0xfd.toByte() ->
                throw IOException(
                    "zstd-compressed layer not supported: ${layerFile.name} " +
                        "(use a gzip-compressed image)",
                )
            else -> raw
        }
        return TarArchiveInputStream(stream)
    }
}
