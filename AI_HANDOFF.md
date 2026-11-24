# Jenyok — AI handoff and recovery guide

This repository is the canonical project location: https://github.com/hgamef72-jpg/Jenyok

## Read these files first
1. `RECOVERY/README.md` — where the recovered Windows source and original binaries came from, their integrity and limitations.
2. `TASKS/V1.0.3-IMPLEMENTATION-PROMPT.md` — the complete implementation brief and acceptance criteria.
3. Existing GitHub workflows and Android sources under `.github/workflows/` and `android-viewer/`.

## Project rules
- Continue the existing Jenyok project. Do not create a replacement project or rewrite it from scratch.
- Preserve v1.0.2 and all existing release assets. Work on a separate branch and make a pull request.
- Do not invent relay protocol messages. Trace every command through the current Windows Host/Viewer implementation before using it on Android.
- Preserve existing working behavior. Avoid unnecessary backup systems or unrelated features.
- Do not claim a build, runtime test, APK installation, or release succeeded unless the corresponding command/device test actually passed. Record exact commands and results.
- Keep secrets, tokens, private relay credentials, and user data out of commits and logs.
- Increment versions by 0.0.1 for subsequent releases. The target for this task is v1.0.3.

## Recovery source
The recovered archive is named `Jenyok-1.5.77-source-recovery.zip`. It was restored from the original Windows portable packages using decompilation. It includes Kotlin source, original JARs, runtime dependencies, CFR Java cross-reference output, and recovery metadata. It is not the original authoring source: decompiled Kotlin contains constructs marked `<unrepresentable>` and requires manual reconstruction. See `RECOVERY/README.md`.

The Android source is tracked directly in this repository under `android-viewer/`. The Android app in the v1.0.2 workflow was explicitly described as an early native implementation; compile/signature checks did not establish runtime parity with desktop.

## Expected workflow for any AI or developer
1. Inspect the repository tree, current branch, latest commits, releases, workflows, and the recovered source before editing.
2. Read the recovery notes and the full implementation prompt.
3. Build a protocol map from Host and Windows Viewer. Cite source file and method for each protocol action.
4. Establish reproducible baseline builds before changing behavior.
5. Implement in small, reviewable commits on a feature branch.
6. Run static checks, Windows packaging/integrity checks, Android build/signature checks, and device/runtime tests where hardware is available.
7. Update the status report with passed, failed, and not-run tests.
8. Open a PR. Do not publish v1.0.3 until all release gates are met.

## Important limitation
A documentation handoff is not a substitute for committing the actual recovered source tree. The recovery archive must be added as a GitHub release asset or the source files must be imported into a dedicated source directory before another AI can clone this repository and build the recovered Windows code from scratch. Do not pretend otherwise.
