# Recovered Windows source

## Artifact
- Archive: `Jenyok-1.5.77-source-recovery.zip`
- Local recovery workspace used for this task: `/mnt/data/Jenyok-recovered/work/source/Jenyok-1.5.77-source-recovery/`
- The archive passed `unzip -t` in the recovery workspace.
- A wrapper backup archive also exists in the ChatGPT file library: `Jenyok-RECOVERY-BACKUP-2026-11-24.zip`.

The archive contains:
- `Host/` — decompiled Kotlin files, config and resources
- `Viewer/` — decompiled Kotlin files, config and resources
- `binaries/Host-original.jar` and `binaries/Viewer-original.jar`
- `binaries/jenyok-runtime-dependencies.jar`
- `alternate-cfr-java/` — CFR Java output for cross-reference only
- `recovery-metadata.json` and README

## Provenance and integrity
The recovered Windows source was reconstructed from the v1.5.77 portable Host and Viewer distributions using Vineflower 1.12.0. It is decompiled output, not the original Kotlin authoring source.

Recorded SHA-256 values:
- Host portable ZIP: `9a55ee42b70e877af65793e888bf35f5923e307e099490f525934b0e595d99d7`
- Viewer portable ZIP: `d17e80da197e39c7a80c7cd7d2c17585923630758d13cb6fb8fea818ee41bce5`
- Application JAR: `a6eb01ae5e9393bfa4ed8a28d68f438be9f4b92078a6e14edcddb6180a1b1010`

The Host and Viewer distributions contain the same application JAR. The recovery metadata and original JARs should be treated as the reference when reconstructing ambiguous code.

## Known decompilation gaps
- Kotlin files contain `<unrepresentable>` markers. These are not valid Kotlin and must be reconstructed manually.
- CFR Java output is only a secondary cross-reference and is not guaranteed to compile.
- A full Kotlin recompilation of the recovered source has not yet been established.
- Relevant locations previously identified:
  - `Viewer/src/main/kotlin/com/saturn/remote/ViewerWindow.kt`: multiple unrepresentable regions around lines 920, 1004, 1031, 1049, 1082, 1107, 1137, 1165, 1186 and 1252.
  - `Host/src/main/kotlin/com/saturn/remote/HostInputBlocker.kt`: around line 115.
  - `Host/src/main/kotlin/com/saturn/remote/HostState.kt`: around lines 887, 905 and 1065.
- Recovery/supervisor implementation is concentrated in `Host/src/main/kotlin/com/saturn/remote/AppsKt.kt`; related startup state is in `HostApp.kt`.

## v1.0.2 relationship
The v1.0.2 publishing workflow downloads the portable Windows packages from release v1.0.1 and republishes them under the v1.0.2 asset names. Therefore the recovered v1.5.77 Windows binaries correspond to the Windows payload carried forward into v1.0.2. Verify this against the workflow before assuming any future release uses the same binaries.

## Android baseline
Android source is in `android-viewer/`. The v1.0.2 workflow runs `gradle --no-daemon assembleDebug` and verifies APK signing/integrity. Its release notes explicitly state that Android was an early native implementation and was not runtime-tested on a physical device; compile success must not be represented as feature parity.

## Current status
- [x] Recovered source archive located in the ChatGPT file library.
- [x] Archive materialized and extracted in the current working environment.
- [x] ZIP integrity test passed.
- [ ] Recovered Windows source committed to this GitHub repository.
- [ ] Kotlin decompilation gaps reconstructed.
- [ ] Recovered Windows source compiles reproducibly.
- [ ] Windows Recovery behavior verified end-to-end.
- [ ] Android feature parity implemented and tested.
- [ ] v1.0.3 release gates passed.

Do not mark unchecked items complete without evidence.
