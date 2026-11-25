'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { spawn } = require('node:child_process');
const WebSocket = require('ws');
const http = require('node:http');

const port = 20000 + Math.floor(Math.random() * 1000);
const base = `ws://127.0.0.1:${port}`;

function waitForMessage(ws, predicate, timeout = 3000) {
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

test('routes Recovery to the Host local watchdog when the Agent is offline', async (t) => {
  const child = spawn(process.execPath, ['src/index.js'], {
    cwd: __dirname + '/..',
    env: { ...process.env, PORT: String(port), HEARTBEAT_MS: '1000' },
    stdio: 'ignore',
  });
  t.after(() => { if (!child.killed) child.kill('SIGTERM'); });

  await new Promise((resolve, reject) => {
    const deadline = Date.now() + 5000;
    const poll = () => http.get(`http://127.0.0.1:${port}/healthz`, (res) => {
      res.resume();
      if (res.statusCode === 200) resolve();
      else setTimeout(poll, 100);
    }).on('error', () => Date.now() < deadline ? setTimeout(poll, 100) : reject(new Error('server did not start')));
    poll();
  });

  const host = await connect('room=recovery-room&role=host&hostId=host-local');
  const viewer = await connect('room=recovery-room&role=viewer');
  t.after(() => { host.close(); viewer.close(); });

  host.send('HOST_SESSION_STATUS|1');
  host.send('HOST_RECOVERY_READY|1');

  const hostList = waitForMessage(viewer, (data) => data.toString() === 'HOST_INFO|host-local|UmVtb3RlIFBD|0|1|1');
  viewer.send('GET_HOST_LIST');
  await hostList;

  const selected = waitForMessage(viewer, (data) => data.toString() === 'HOST_SELECTED|host-local');
  const ready = waitForMessage(viewer, (data) => data.toString() === 'HOST_AGENT_READY|1');
  viewer.send('SELECT_HOST|host-local');
  await selected;
  await ready;

  const status = waitForMessage(viewer, (data) => data.toString() === 'HOST_AGENT_READY|1');
  viewer.send('GET_AGENT_STATUS');
  await status;

  const command = waitForMessage(host, (data) => data.toString() === 'RECOVER_HOST');
  viewer.send('RECOVER_HOST');
  await command;

  const accepted = waitForMessage(viewer, (data) => data.toString() === 'RECOVERY_ACCEPTED|1');
  host.send('RECOVERY_ACCEPTED|1');
  assert.equal((await accepted).data.toString(), 'RECOVERY_ACCEPTED|1');
});
