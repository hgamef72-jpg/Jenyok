'use strict';

const http = require('node:http');
const { URL } = require('node:url');
const WebSocket = require('ws');

const PORT = Number(process.env.PORT || 10000);
const MAX_PAYLOAD = Number(process.env.MAX_PAYLOAD_BYTES || 12 * 1024 * 1024);
const MAX_BUFFERED = Number(process.env.MAX_BUFFERED_BYTES || 8 * 1024 * 1024);
const HEARTBEAT_MS = Number(process.env.HEARTBEAT_MS || 25_000);
const ID_RE = /^[a-zA-Z0-9_.:-]{1,128}$/;
const AGENT_COMMANDS = /^(GET_AGENT_STATUS|GET_BACKUP_STATUS|CREATE_BACKUPS|GET_HOST_STATUS|RECOVER_HOST|DELETE_ALL_BACKUPS|BACKUP_RESTORE\|)/;

const rooms = new Map();
const sockets = new Set();

function getRoom(name) {
  let room = rooms.get(name);
  if (!room) {
    room = { hosts: new Map(), viewers: new Set() };
    rooms.set(name, room);
  }
  return room;
}

function safeSend(ws, data, options = {}) {
  if (!ws || ws.readyState !== WebSocket.OPEN) return false;
  if (ws.bufferedAmount > MAX_BUFFERED) {
    ws.close(1013, 'Receiver is too slow');
    return false;
  }
  try {
    ws.send(data, options, (error) => {
      if (error && ws.readyState === WebSocket.OPEN) ws.terminate();
    });
    return true;
  } catch {
    ws.terminate();
    return false;
  }
}

function getHostRecord(room, hostId) {
  let host = room.hosts.get(hostId);
  if (!host) {
    host = { id: hostId, name: 'Remote PC', executable: '', screenCount: 0, online: false, agentReady: false, ws: null, agent: null };
    room.hosts.set(hostId, host);
  }
  return host;
}

function resolveHostId(viewer) {
  if (viewer.selectedHostId && viewer.roomState.hosts.has(viewer.selectedHostId)) return viewer.selectedHostId;
  if (viewer.roomState.hosts.size === 1) return viewer.roomState.hosts.keys().next().value;
  return null;
}

function hostInfo(host) {
  const name = Buffer.from(host.name || 'Remote PC', 'utf8').toString('base64url');
  return `HOST_INFO|${host.id}|${name}|${host.screenCount}|${host.agentReady ? '1' : '0'}|${host.online ? '1' : '0'}`;
}

function sendHostList(viewer) {
  safeSend(viewer, 'HOST_LIST_BEGIN');
  for (const host of viewer.roomState.hosts.values()) safeSend(viewer, hostInfo(host));
  safeSend(viewer, 'HOST_LIST_END');
}

function notifyHostList(room) {
  for (const viewer of room.viewers) sendHostList(viewer);
}

function forwardToSelectedViewers(host, message, binary = false) {
  for (const viewer of host.roomState.viewers) {
    if (resolveHostId(viewer) === host.id) safeSend(viewer, message, { binary });
  }
}

function sendToHost(viewer, data, binary = false) {
  const hostId = resolveHostId(viewer);
  const host = hostId ? viewer.roomState.hosts.get(hostId) : null;
  if (!host) {
    safeSend(viewer, 'HOST_SESSION_STATUS|0');
    return;
  }
  if (typeof data === 'string' && AGENT_COMMANDS.test(data) && host.agent?.readyState === WebSocket.OPEN) {
    safeSend(host.agent, data, { binary });
    return;
  }
  if (host.ws?.readyState === WebSocket.OPEN && host.online) {
    safeSend(host.ws, data, { binary });
    return;
  }
  if (host.agent?.readyState === WebSocket.OPEN) {
    safeSend(host.agent, data, { binary });
    return;
  }
  safeSend(viewer, 'HOST_SESSION_STATUS|0');
}

function pruneHost(room, host) {
  if (!host.ws && !host.agent) room.hosts.delete(host.id);
  if (room.hosts.size === 0 && room.viewers.size === 0) rooms.delete(host.roomName);
}

function cleanup(ws) {
  if (ws.cleaned) return;
  ws.cleaned = true;
  sockets.delete(ws);
  const room = ws.roomState;
  if (!room) return;

  if (ws.role === 'host' || ws.role === 'agent') {
    const host = room.hosts.get(ws.hostId);
    if (host) {
      if (ws.role === 'host' && host.ws === ws) {
        host.ws = null;
        host.online = false;
        host.screenCount = 0;
        forwardToSelectedViewers(host, 'HOST_SESSION_STATUS|0');
      } else if (ws.role === 'agent' && host.agent === ws) {
        host.agent = null;
        host.agentReady = false;
      }
      notifyHostList(room);
      pruneHost(room, host);
    }
  } else {
    room.viewers.delete(ws);
  }
  if (room.hosts.size === 0 && room.viewers.size === 0) rooms.delete(ws.roomName);
}

function updateHostState(host, message) {
  const parts = message.split('|');
  switch (parts[0]) {
    case 'HOST_IDENTITY':
      host.name = (parts[1] || 'Remote PC').slice(0, 128);
      host.executable = (parts[2] || '').slice(0, 128);
      break;
    case 'SCREEN_COUNT': {
      const count = Number(parts[1]);
      if (Number.isInteger(count)) host.screenCount = Math.max(0, Math.min(32, count));
      break;
    }
    case 'HOST_SESSION_STATUS':
      host.online = parts[1] === '1';
      break;
    case 'HOST_AGENT_READY':
      host.agentReady = parts[1] === '1';
      break;
  }
}

function routeText(ws, text) {
  if (Buffer.byteLength(text, 'utf8') > 64 * 1024) {
    ws.close(1009, 'Text message too large');
    return;
  }

  if (ws.role === 'host' || ws.role === 'agent') {
    const host = ws.roomState.hosts.get(ws.hostId);
    if (!host || (ws.role === 'host' ? host.ws !== ws : host.agent !== ws)) return;
    updateHostState(host, text);
    if (/^(SCREEN_COUNT|HOST_IDENTITY|HOST_SESSION_STATUS|HOST_AGENT_READY)\|/.test(text)) notifyHostList(ws.roomState);
    forwardToSelectedViewers(host, text);
    return;
  }

  if (text === 'GET_HOST_LIST') {
    sendHostList(ws);
    return;
  }
  if (text.startsWith('SELECT_HOST|')) {
    const hostId = text.slice('SELECT_HOST|'.length);
    if (!ID_RE.test(hostId)) return;
    ws.selectedHostId = hostId;
    const host = ws.roomState.hosts.get(hostId);
    if (!host) {
      safeSend(ws, 'HOST_SESSION_STATUS|0');
      return;
    }
    if (host.ws?.readyState === WebSocket.OPEN) safeSend(host.ws, 'GET_HOST_STATUS');
    else if (host.agent?.readyState === WebSocket.OPEN) safeSend(host.agent, 'GET_HOST_STATUS');
    else safeSend(ws, 'HOST_SESSION_STATUS|0');
    return;
  }
  sendToHost(ws, text, false);
}

const server = http.createServer((req, res) => {
  if (req.url === '/' || req.url === '/health' || req.url === '/healthz') {
    res.writeHead(200, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' });
    res.end(JSON.stringify({ ok: true, service: 'jenyok-relay', rooms: rooms.size, sockets: sockets.size }));
    return;
  }
  res.writeHead(404, { 'content-type': 'text/plain; charset=utf-8' });
  res.end('Not found');
});

const wss = new WebSocket.Server({ noServer: true, maxPayload: MAX_PAYLOAD, perMessageDeflate: false, clientTracking: false });

server.on('upgrade', (req, socket, head) => {
  let url;
  try { url = new URL(req.url, 'http://localhost'); } catch { socket.destroy(); return; }
  if (url.pathname !== '/connect') { socket.destroy(); return; }
  const roomName = url.searchParams.get('room') || '';
  const role = url.searchParams.get('role') || '';
  const hostId = url.searchParams.get('hostId') || '';
  if (!ID_RE.test(roomName) || !['host', 'viewer', 'agent'].includes(role) || ((role === 'host' || role === 'agent') && !ID_RE.test(hostId))) {
    socket.write('HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n');
    socket.destroy();
    return;
  }
  wss.handleUpgrade(req, socket, head, (ws) => {
    ws.roomName = roomName;
    ws.role = role;
    ws.hostId = role === 'viewer' ? null : hostId;
    ws.roomState = getRoom(roomName);
    ws.isAlive = true;
    sockets.add(ws);

    if (role === 'host' || role === 'agent') {
      const host = getHostRecord(ws.roomState, hostId);
      host.roomName = roomName;
      host.roomState = ws.roomState;
      if (role === 'host') {
        if (host.ws && host.ws !== ws) host.ws.close(4001, 'Host reconnected');
        host.ws = ws;
        host.online = true;
      } else {
        if (host.agent && host.agent !== ws) host.agent.close(4001, 'Agent reconnected');
        host.agent = ws;
        host.agentReady = true;
      }
      notifyHostList(ws.roomState);
    } else {
      ws.selectedHostId = null;
      ws.roomState.viewers.add(ws);
    }
    wss.emit('connection', ws, req);
  });
});

wss.on('connection', (ws) => {
  ws.on('pong', () => { ws.isAlive = true; });
  ws.on('message', (data, isBinary) => {
    if (isBinary) {
      if (ws.role === 'host') {
        const host = ws.roomState.hosts.get(ws.hostId);
        if (host && host.ws === ws) forwardToSelectedViewers(host, data, true);
      } else if (ws.role === 'viewer') sendToHost(ws, data, true);
      return;
    }
    routeText(ws, data.toString('utf8'));
  });
  ws.on('close', () => cleanup(ws));
  ws.on('error', () => cleanup(ws));
});

const heartbeat = setInterval(() => {
  for (const ws of sockets) {
    if (ws.readyState !== WebSocket.OPEN) { cleanup(ws); continue; }
    if (!ws.isAlive) { ws.terminate(); cleanup(ws); continue; }
    ws.isAlive = false;
    try { ws.ping(); } catch { ws.terminate(); cleanup(ws); }
  }
}, HEARTBEAT_MS);
heartbeat.unref();

server.listen(PORT, '0.0.0.0', () => process.stdout.write(`Jenyok Relay listening on ${PORT}\n`));

function shutdown() {
  clearInterval(heartbeat);
  for (const ws of sockets) ws.close(1001, 'Server shutting down');
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(1), 5_000).unref();
}
process.on('SIGTERM', shutdown);
process.on('SIGINT', shutdown);
