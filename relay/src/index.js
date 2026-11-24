'use strict';

const http = require('node:http');
const { URL } = require('node:url');
const WebSocket = require('ws');

const PORT = Number(process.env.PORT || 10000);
const MAX_PAYLOAD = Number(process.env.MAX_PAYLOAD_BYTES || 12 * 1024 * 1024);
const MAX_BUFFERED = Number(process.env.MAX_BUFFERED_BYTES || 8 * 1024 * 1024);
const HEARTBEAT_MS = Number(process.env.HEARTBEAT_MS || 25_000);
const ROOM_RE = /^[a-zA-Z0-9_.:-]{1,128}$/;
const HOST_ID_RE = /^[a-zA-Z0-9_.:-]{1,128}$/;

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
  if (ws.readyState !== WebSocket.OPEN) return false;
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

function hostInfo(hostId, host) {
  const encodedName = Buffer.from(host.name || 'Remote PC', 'utf8').toString('base64url');
  return `HOST_INFO|${hostId}|${encodedName}|${host.screenCount}|${host.agentReady ? '1' : '0'}|${host.online ? '1' : '0'}`;
}

function sendHostList(viewer) {
  const room = viewer.roomState;
  safeSend(viewer, 'HOST_LIST_BEGIN');
  for (const [hostId, host] of room.hosts) safeSend(viewer, hostInfo(hostId, host));
  safeSend(viewer, 'HOST_LIST_END');
}

function notifyHostList(room) {
  for (const viewer of room.viewers) sendHostList(viewer);
}

function sendToHost(viewer, data, isBinary = false) {
  const host = viewer.roomState.hosts.get(viewer.selectedHostId);
  if (!host || !host.online || !host.ws) {
    safeSend(viewer, 'HOST_SESSION_STATUS|0');
    return;
  }
  safeSend(host.ws, data, { binary: isBinary });
}

function cleanup(ws) {
  if (ws.cleaned) return;
  ws.cleaned = true;
  sockets.delete(ws);
  const room = ws.roomState;
  if (!room) return;

  if (ws.role === 'host') {
    const host = room.hosts.get(ws.hostId);
    if (host && host.ws === ws) {
      host.ws = null;
      host.online = false;
      host.screenCount = 0;
      host.agentReady = false;
      notifyHostList(room);
      for (const viewer of room.viewers) {
        if (viewer.selectedHostId === ws.hostId) safeSend(viewer, 'HOST_SESSION_STATUS|0');
      }
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
      const n = Number(parts[1]);
      if (Number.isInteger(n)) host.screenCount = Math.max(0, Math.min(32, n));
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
  if (ws.role === 'host') {
    const host = ws.roomState.hosts.get(ws.hostId);
    if (!host || host.ws !== ws) return;
    updateHostState(host, text);
    if (/^(SCREEN_COUNT|HOST_IDENTITY|HOST_SESSION_STATUS|HOST_AGENT_READY)\|/.test(text)) {
      notifyHostList(ws.roomState);
    }
    for (const viewer of ws.roomState.viewers) {
      if (viewer.selectedHostId === ws.hostId) safeSend(viewer, text);
    }
    return;
  }

  if (text === 'GET_HOST_LIST') {
    sendHostList(ws);
    return;
  }
  if (text.startsWith('SELECT_HOST|')) {
    const hostId = text.slice('SELECT_HOST|'.length);
    if (!HOST_ID_RE.test(hostId)) return;
    ws.selectedHostId = hostId;
    const host = ws.roomState.hosts.get(hostId);
    if (!host || !host.online || !host.ws) {
      safeSend(ws, 'HOST_SESSION_STATUS|0');
      return;
    }
    safeSend(host.ws, 'GET_HOST_STATUS');
    return;
  }
  sendToHost(ws, text, false);
}

const server = http.createServer((req, res) => {
  if (req.url === '/' || req.url === '/health' || req.url === '/healthz') {
    const body = JSON.stringify({ ok: true, service: 'jenyok-relay', rooms: rooms.size, sockets: sockets.size });
    res.writeHead(200, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' });
    res.end(body);
    return;
  }
  res.writeHead(404, { 'content-type': 'text/plain; charset=utf-8' });
  res.end('Not found');
});

const wss = new WebSocket.Server({
  noServer: true,
  maxPayload: MAX_PAYLOAD,
  perMessageDeflate: false,
  clientTracking: false,
});

server.on('upgrade', (req, socket, head) => {
  let url;
  try { url = new URL(req.url, 'http://localhost'); } catch { socket.destroy(); return; }
  if (url.pathname !== '/connect') { socket.destroy(); return; }
  const roomName = url.searchParams.get('room') || '';
  const role = url.searchParams.get('role') || '';
  const hostId = url.searchParams.get('hostId') || '';
  if (!ROOM_RE.test(roomName) || !['host', 'viewer'].includes(role) || (role === 'host' && !HOST_ID_RE.test(hostId))) {
    socket.write('HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n');
    socket.destroy();
    return;
  }
  wss.handleUpgrade(req, socket, head, (ws) => {
    ws.roomName = roomName;
    ws.role = role;
    ws.hostId = role === 'host' ? hostId : null;
    ws.roomState = getRoom(roomName);
    ws.isAlive = true;
    sockets.add(ws);
    if (role === 'host') {
      const previous = ws.roomState.hosts.get(hostId);
      if (previous?.ws && previous.ws !== ws) previous.ws.close(4001, 'Host reconnected');
      const host = previous || { name: 'Remote PC', executable: '', screenCount: 0, online: true, agentReady: false, ws: null };
      host.ws = ws;
      host.online = true;
      ws.roomState.hosts.set(hostId, host);
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
        for (const viewer of ws.roomState.viewers) {
          if (viewer.selectedHostId === ws.hostId) safeSend(viewer, data, { binary: true });
        }
      } else {
        sendToHost(ws, data, true);
      }
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

server.listen(PORT, '0.0.0.0', () => {
  process.stdout.write(`Jenyok Relay listening on ${PORT}\n`);
});

function shutdown() {
  clearInterval(heartbeat);
  for (const ws of sockets) ws.close(1001, 'Server shutting down');
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(1), 5_000).unref();
}
process.on('SIGTERM', shutdown);
process.on('SIGINT', shutdown);
