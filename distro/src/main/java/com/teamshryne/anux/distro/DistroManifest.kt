package com.teamshryne.anux.distro

/** CPU architectures, mirroring proot-distro arch.py. */
enum class CpuArch(val dockerArch: String, val dockerVariant: String? = null) {
    AARCH64("arm64", "v8"),
    ARM("arm", "v7"),
    I686("386"),
    X86_64("amd64"),
    RISCV64("riscv64"),
    UNKNOWN("amd64");

    companion object {
        fun normalize(unameM: String): CpuArch = when (unameM.lowercase()) {
            "aarch64", "arm64" -> AARCH64
            "armv7l", "armv8l", "arm" -> ARM
            "i386", "i686" -> I686
            "x86_64", "amd64" -> X86_64
            "riscv64" -> RISCV64
            else -> UNKNOWN
        }

        fun deviceArch(): CpuArch = try {
            normalize(System.getProperty("os.arch").orEmpty().ifEmpty { "aarch64" })
        } catch (_: Exception) {
            AARCH64 // Android devices are overwhelmingly aarch64; refined at runtime via Build.SUPPORTED_ABIS.
        }
    }
}

enum class DistType { NORMAL, TERMUX }

/**
 * Curated distro entry. proot-distro resolves these as OCI refs
 * (ubuntu -> library/ubuntu:latest on Docker Hub); no static tarball URLs.
 */
data class DistroManifest(
    val alias: String,
    val displayName: String,
    val imageRef: String,
    val defaultUser: String = "root",
    val description: String = "",
)

/** Built-in catalog for v1. Sizes are approximate compressed layer sizes. */
object DistroCatalog {
    val all = listOf(
        DistroManifest("alpine", "Alpine", "alpine:latest", description = "Tiny (~30MB). Fastest first boot."),
        DistroManifest("ubuntu", "Ubuntu 24.04", "ubuntu:24.04", description = "Batteries included. What most users want."),
        DistroManifest("debian", "Debian", "debian:bookworm", description = "Stable workhorse."),
        DistroManifest("arch", "Arch Linux", "archlinux:latest", description = "Rolling release."),
        DistroManifest("fedora", "Fedora", "fedora:latest", description = "DNF-based alternative."),
    )
    fun byAlias(alias: String): DistroManifest? = all.find { it.alias == alias }
}
