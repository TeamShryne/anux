# anux — Linux on Android, no root

Modern multi-distro proot launcher with a terminal-first UI. Kotlin + Jetpack Compose,
Termux's `terminal-view`/`terminal-emulator` (vendored), Termux proot fork, OCI-based
distro installs (proot-distro model).

- No root required (proot + ptrace, Termux-compatible layout under `files/`).
- Multi-distro: Alpine, Ubuntu, Debian, Arch, Fedora + custom OCI refs.
- Terminal-first Compose UI: session chips, extra-keys row, foreground service.

## Modules

- `app/` — Compose UI, `AnuxService` (foreground, N sessions), Room registry,
  DataStore settings, WorkManager installs, backup/restore via Storage Access.
- `distro/` — pure-JVM engine: OCI registry client, layer extractor (whiteouts,
  strip detection), installer, backup/restore, proot argv builder. Fully unit-tested.
- `terminal-view/`, `terminal-emulator/` — vendored from termux-app (unmodified).

## Dev setup

Proot binaries are **not** committed; they are fetched from Termux's APT repo
(`scripts/fetch-proot.sh`, pinned versions) into `app/src/main/assets/proot/`.
Gradle runs the script automatically on `preBuild`; CI caches the output.

```bash
./gradlew :distro:test :app:testDebugUnitTest assembleDebug
```

Installs download gzipped OCI layers (zstd layers are rejected with a clear error),
verify sha256 on every blob (even cache hits), then unpack with whiteout support.
