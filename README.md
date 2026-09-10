# anux — Linux on Android, no root

Modern multi-distro proot launcher with a terminal-first UI. Kotlin + Jetpack Compose,
Termux's `terminal-view`/`terminal-emulator` (vendored), Termux proot fork, OCI-based
distro installs (proot-distro model).

- No root required (proot + ptrace, Termux-compatible layout under `files/`).
- Multi-distro: Alpine, Ubuntu, Debian, Arch, Fedora + custom OCI refs.
- Terminal-first Compose UI: session chips, extra-keys row, foreground service.

Docs: `docs/` (planned). Status: early scaffold — CI builds debug APK, bootstrap +
distro install land incrementally.
