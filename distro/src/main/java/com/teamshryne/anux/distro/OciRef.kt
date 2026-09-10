package com.teamshryne.anux.distro

import java.io.File

/**
 * OCI image-ref parsing, ported from proot-distro helpers/docker/refs.py.
 * ubuntu -> library/ubuntu:latest (Docker Hub); missing tag -> :latest.
 */
object OciRef {
    data class Parsed(
        val registry: String, // "" = Docker Hub
        val repo: String,
        val tag: String,
        val canonicalRef: String,
        val alias: String,
    )

    fun parse(ref: String): Parsed {
        val trimmed = ref.trim()
        require(trimmed.isNotEmpty()) { "empty image ref" }
        val withoutDigest = trimmed.substringBefore("@")
        val repoAndTag = withoutDigest
        val tag = if (":" in repoAndTag.substringAfterLast("/")) {
            repoAndTag.substringAfterLast(":")
        } else {
            "latest"
        }
        val repoPart = if (tag == "latest" && ":" !in repoAndTag.substringAfterLast("/")) {
            repoAndTag
        } else {
            repoAndTag.substringBeforeLast(":")
        }
        val first = repoPart.substringBefore("/")
        val (registry, repo) = if ("." in first || ":" in first || repoPart.contains("/").not()) {
            if (repoPart.contains("/").not()) {
                "" to "library/$repoPart"
            } else if ("." in first || ":" in first) {
                first to repoPart.substringAfter("/")
            } else {
                "" to repoPart
            }
        } else {
            "" to repoPart
        }
        val canonical = buildString {
            if (registry.isNotEmpty()) append("$registry/")
            append(repo)
            append(":$tag")
        }
        return Parsed(registry, repo, tag, canonical, repo.substringAfterLast("/"))
    }

    fun localName(imageRef: String): String =
        imageRef.substringBefore("@").substringAfterLast("/").substringBefore(":")
            .replace(Regex("[^A-Za-z0-9_.-]"), "_").ifEmpty { "custom" }

    fun containerDir(filesDir: File, alias: String): File = File(filesDir, "containers/$alias")
    fun rootfsDir(filesDir: File, alias: String): File = File(containerDir(filesDir, alias), "rootfs")
    fun isInstalled(filesDir: File, alias: String): Boolean =
        File(rootfsDir(filesDir, alias), "bin").isDirectory ||
            File(rootfsDir(filesDir, alias), "usr/bin").isDirectory
}
