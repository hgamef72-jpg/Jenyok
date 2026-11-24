'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { spawn } = require('node:child_process');
const WebSocket = require('ws');
const http = require('node:http');

const port = 19000 + Math.floor(Math.random() * 1000);
const base = `ws://127.0.0.1:${port}`;
let child;

function waitForMessage(ws, predicate, timeout = 2500) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('message timeout')), timeout);
    const onMessage = (data, binary) => {
      if (predicate(data, binary)) {
        clearTimeout(timer);
        ws.off('message', onMessage);
        resolve({ data, binary });
      }
    };
    ws.on('message', onMessage);
  });
}
function connect(query) {
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(`${base}/connect?${query}`);
    ws.once('open', () => resolve(ws));
    ws.once('error', reject);
  });
}

test('routes host discovery, text commands and binary frames', async (t) => {
  child = spawn(process.execPath, ['src/index.js'], {
    cwd: __dirname + '/..',
    env: { ...process.env, PORT: String(port), HEARTBEAT_MS: '1000' },
    stdio: 'ignore',
  });
  t.after(() => { if (child && !child.killed) child.kill('SIGTERM'); });
  await new Promise((resolve, reject) => {
    const deadline = Date.now() + 5000;
    const poll = () => http.get(`http://127.0.0.1:${port}/healthz`, (res) => {
      res.resume();
      if (res.statusCode === 200) resolve(); else setTimeout(poll, 100);
    }).on('error', () => Date.now() < deadline ? setTimeout(poll, 100) : reject(new Error('server did not start')));
    poll();
  });
  const host = await connect('room=test-room&role=host&hostId=host-1');
  const viewer = await connect('room=test-room&role=viewer');
  t.after(() => { host.close(); viewer.close(); });
  host.send('HOST_IDENTITY|Test PC|Host.exe');
  host.send('SCREEN_COUNT|2');
  host.send('HOST_SESSION_STATUS|1');
  viewer.send('GET_HOST_LIST');
  const info = await waitForMessage(viewer, (data) => data.toString().startsWith('HOST_INFO|'));
  assert.match(info.data.toString(), /^HOST_INFO\\|host-1\\|VGVzdCBQQw\\|2\\|0\\|1$/);
  viewer.send('SELECT_HOST|host-1');
  const forwarded = waitForMessage(host, (data) => data.toString() === 'REMOTE_CONTROL_REQUEST|1');
  viewer.send('REMOTE_CONTROL_REQUEST|1');
  await forwarded;
  const binary = Buffer.from([0xff, 0xd8, 0x01, 0x02, 0xff, 0xd9]);
  const receivedFrame = waitForMessage(viewer, (_data, isBinary) => isBinary);
  host.send(binary);
  assert.deepEqual(Buffer.from((await receivedFrame).data), binary);
});
