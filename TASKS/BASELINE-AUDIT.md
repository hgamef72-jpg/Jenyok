# Baseline code audit — v1.0.2

This report is based on the recovered Windows source archive and the Android files in the repository. It is a static source inspection, not a runtime test.

## Android Viewer defects confirmed by source

File: `android-viewer/app/src/main/java/com/jenyok/viewer/MainActivity.java`

1. **Recovery is absent.** No Recovery UI or `RECOVER_HOST` request/response handling exists.
2. **Block/Unblock is absent.** No `BLOCK_HOST_INPUT_REQUEST|1`, `BLOCK_HOST_INPUT|0`, or heartbeat handling exists.
3. **Monitor selection is absent.** `SCREEN_COUNT`, `SCREEN_INFO`, and `SCREEN_SELECT` are not handled in the Android UI.
4. **Only monitor zero is rendered.** `handleFrame` reads the first four bytes as the monitor index and discards every frame where `monitor != 0`.
5. **Touch protocol framing is incorrect.** Android sends `SCREEN_INPUT|INPUT|MOVE|x|y`. The Windows Host parser expects `SCREEN_INPUT|<monitorIndex>|<inputPayload>`; it parses field 1 as the monitor index and passes the remaining payload to the input handler. The Android string therefore shifts the fields and does not produce the expected `INPUT|MOVE|x|y` payload.
6. **Touch coordinate mapping is incorrect.** The ImageView uses `FIT_CENTER`, but the code normalizes touch coordinates against the full view width/height. It does not remove the letterbox margins or scale against the displayed bitmap bounds.
7. **Connection management is incomplete.** There is no automatic reconnect loop, no lifecycle-aware reconnection policy, and no deterministic host chooser when multiple Hosts are present.
8. **Endpoint is user-editable.** The UI exposes the Relay URL field even though the requested design keeps the endpoint internal.
9. **Orientation is locked.** The manifest declares `sensorLandscape`, preventing the requested portrait layout.
10. **Remote-control heartbeat behavior is incomplete.** It sends a heartbeat while local `control` is true, but does not fully model granted/denied state and reset behavior on connection loss.

## Windows protocol facts confirmed in recovered source

Relevant source files:
- `Host/src/main/kotlin/com/saturn/remote/ViewerWindow.kt`
- `Host/src/main/kotlin/com/saturn/remote/HostState.kt`
- `Host/src/main/kotlin/com/saturn/remote/AppsKt.kt`

Observed command/event pairs:
- Monitor inventory: Host emits `SCREEN_COUNT|<count>`, `SCREEN_INFO|<index>|<x>|<y>|<width>|<height>`, and `SCREEN_SIZE|<selectedIndex>|<x>|<y>|<width>|<height>`.
- Monitor selection: Viewer sends `SCREEN_SELECT|<monitorIndex>`; Host validates the index and emits updated inventory.
- Remote input: Host receives `SCREEN_INPUT|<monitorIndex>|<payload>`; payload begins with `INPUT|`. Host's `handleInput` validates remote-control grant and heartbeat before dispatching.
- Recovery: Viewer sends `RECOVER_HOST`. The embedded Recovery Agent replies with `RECOVERY_ACCEPTED|1` or `RECOVERY_ERROR|<base64url-message>`.
- Agent availability: Viewer periodically sends `GET_AGENT_STATUS`; agent replies `HOST_AGENT_READY|1`.
- Host session: Viewer periodically sends `GET_HOST_STATUS`; agent replies `HOST_SESSION_STATUS|0|1`.
- Remote control: `REMOTE_CONTROL_REQUEST|1`, `REMOTE_CONTROL|0`, and `REMOTE_CONTROL_HEARTBEAT`.
- Host input block: `BLOCK_HOST_INPUT_REQUEST|1`, `BLOCK_HOST_INPUT|0`, and `BLOCK_HOST_INPUT_HEARTBEAT`.
- Process trigger sync: `PROCESS_TRIGGERS_GET` and the `PROCESS_TRIGGERS_BEGIN/ITEM/END` response sequence.

These are observations from the recovered implementation. Before using them on Android, trace the full relay routing and Host handler and preserve the exact framing.

## Windows Recovery investigation — not yet a confirmed root cause

The Windows Viewer source:
- Creates a Recovery button disabled by default.
- Sends `RECOVER_HOST` only when the Viewer is connected and has received `HOST_AGENT_READY|1`.
- Updates button enabled state from `HOST_AGENT_READY` and connection state.
- Shows acceptance/error status from the Recovery Agent.

The embedded agent in `AppsKt.kt`:
- Handles `GET_AGENT_STATUS` and returns `HOST_AGENT_READY|1`.
- Handles `RECOVER_HOST` by writing a timestamp to the configured request file and returning `RECOVERY_ACCEPTED|1`.
- The supervisor loop watches that request file, stops the existing Host process, waits for it to exit, then launches the configured executable.
- It writes diagnostic information to the Recovery log.

This establishes that the button's gray/disabled state can occur before a Recovery click if the Viewer never receives the agent-ready response. It does **not** establish why a particular user's agent is unavailable. Runtime logs, scheduled-task state, relay routing and the exact request/status paths must be checked on Windows before claiming a root cause.

## Next implementation sequence
1. Import the recovered source archive into this repository or publish it as an immutable GitHub release asset, including SHA-256 checksums. Until this is done, a clean clone cannot rebuild Windows from the recovered Kotlin files.
2. Establish Windows source build configuration and repair the decompilation gaps.
3. Diagnose Recovery availability and request flow on an actual Windows Host/Viewer pair.
4. Implement Android protocol parity, starting with correct monitor-index framing and letterbox-aware touch mapping.
5. Add monitor UI, Recovery, Block/Unblock, reconnect, discovery, and responsive orientation support.
6. Run the full tests from `TASKS/V1.0.3-IMPLEMENTATION-PROMPT.md`.
