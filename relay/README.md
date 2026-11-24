# Jenyok Relay

WebSocket relay reconstructed from the recovered Host and Viewer protocol.

## Run

```sh
npm install
npm test
npm start
```

Render configuration: Node runtime, root directory `relay`, build command `npm install`, start command `npm start`. Health endpoint: `/healthz`.

## Wire protocol

- Host: `/connect?room=<room>&role=host&hostId=<id>`
- Viewer: `/connect?room=<room>&role=viewer`
- Recovery Agent: `/connect?room=<room>&role=agent&hostId=<id>`
- Viewer discovery: `GET_HOST_LIST`, response `HOST_LIST_BEGIN`, `HOST_INFO|<id>|<base64url-name>|<screen-count>|<agent-ready>|<online>`, `HOST_LIST_END`.
- Viewer selects a host with `SELECT_HOST|<id>`; subsequent text commands and binary input are routed to that Host.
- Host text responses and binary screen frames are forwarded only to Viewers selecting that Host.
- Recovery Agent commands (`GET_AGENT_STATUS`, `RECOVER_HOST`, backup status/restore operations) are routed to the Agent socket, including while the main Host is offline.

The service caps WebSocket message size, queued output, total connections, rooms, Hosts per room and Viewers per room; disables per-message compression; removes disconnected peers; and uses ping/pong liveness checks. The legacy public room has no client authentication; this first implementation preserves that legacy connection contract. Do not expose additional private rooms without coordinated client authentication.

## Compatibility status

Protocol reconstruction is based on recovered `HostState.kt` and `ViewerWindow.kt`. Agent/recovery side-channel behavior and end-to-end Android/Windows runtime compatibility remain to be verified before release.

The integration suite waits for the fully populated discovery record before asserting routing behavior.
