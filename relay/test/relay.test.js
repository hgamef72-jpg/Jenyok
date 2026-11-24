'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { spawn } = require('node:child_process');
const WebSocket = require('ws');
const http = require('node:http');

const port = 19000 + Math.floor(Math.random() * 1000);
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

test('routes Host, Viewer, Agent, text commands and binary frames', async (t) => {
  const child = spawn(process.execPath, ['src/index.js'], {
    cwd: __dirname + '/..',
    env: { ...process.env, PORT: String(port), HEARTBEAT_MS: '1000', MAX_VIEWERS_PER_ROOM: '1', MAX_BUFFERED_BYTES: String(1024 * 1024), MAX_PAYLOAD_BYTES: String(4 * 1024 * 1024) },
    stdio: 'ignore',
  });
  t.after(() => { if (!child.killed) child.kill('SIGTERM'); });
  await new Promise((resolve, reject) => {
    const deadline = Date.now() + 5000;
    const poll = () => http.get(`http://127.0.0.1:${port}/healthz`, (res) => {
      res.resume();
      if (res.statusCode === 200) resolve(); else setTimeout(poll, 100);
    }).on('error', () => Date.now() < deadline ? setTimeout(poll, 100) : reject(new Error('server did not start')));
    poll();
  });

  const host = await connect('room=test-room&role=host&hostId=host-1');
  const agent = await connect('room=test-room&role=agent&hostId=host-1');
  const viewer = await connect('room=test-room&role=viewer');
  t.after(() => { host.close(); agent.close(); viewer.close(); });
  await assert.rejects(connect('room=test-room&role=viewer'), /Unexpected server response: 429/);

  host.send('HOST_IDENTITY|Test PC|Host.exe');
  host.send('SCREEN_COUNT|2');
  host.send('HOST_SESSION_STATUS|0');
  agent.send('HOST_AGENT_READY|1');
  viewer.send('GET_HOST_LIST');
  const info = await waitForMessage(viewer, (data) => data.toString() === 'HOST_INFO|host-1|VGVzdCBQQw|2|0|1');
  assert.equal(info.data.toString(), 'HOST_INFO|host-1|VGVzdCBQQw|2|1|1');

  viewer.send('SELECT_HOST|host-1');
  const hostCommand = waitForMessage(host, (data) => data.toString() === 'REMOTE_CONTROL_REQUEST|1');
  viewer.send('REMOTE_CONTROL_REQUEST|1');
  await hostCommand;

  const controlStatus = waitForMessage(viewer, (data) => data.toString() === 'REMOTE_CONTROL_STATUS|1');
  host.send('REMOTE_CONTROL_STATUS|1');
  await controlStatus;

  const blockRequest = waitForMessage(host, (data) => data.toString() === 'BLOCK_HOST_INPUT_REQUEST|1');
  viewer.send('BLOCK_HOST_INPUT_REQUEST|1');
  await blockRequest;

  const blockStatus = waitForMessage(viewer, (data) => data.toString() === 'BLOCK_HOST_INPUT_STATUS|1');
  host.send('BLOCK_HOST_INPUT_STATUS|1');
  await blockStatus;

  const remoteHeartbeat = waitForMessage(host, (data) => data.toString() === 'REMOTE_CONTROL_HEARTBEAT');
  viewer.send('REMOTE_CONTROL_HEARTBEAT');
  await remoteHeartbeat;

  const blockHeartbeat = waitForMessage(host, (data) => data.toString() === 'BLOCK_HOST_INPUT_HEARTBEAT');
  viewer.send('BLOCK_HOST_INPUT_HEARTBEAT');
  await blockHeartbeat;

  const createBackups = waitForMessage(host, (data) => data.toString() === 'CREATE_BACKUPS');
  viewer.send('CREATE_BACKUPS');
  await createBackups;

  const backupStatus = waitForMessage(host, (data) => data.toString() === 'GET_BACKUP_STATUS');
  viewer.send('GET_BACKUP_STATUS');
  await backupStatus;

  const deleteBackups = waitForMessage(host, (data) => data.toString() === 'DELETE_ALL_BACKUPS');
  viewer.send('DELETE_ALL_BACKUPS');
  await deleteBackups;

  const hostOfflineStatus = waitForMessage(viewer, (data) => data.toString() === 'HOST_SESSION_STATUS|0');
  host.send('HOST_SESSION_STATUS|0');
  await hostOfflineStatus;

  const restoreBackup = waitForMessage(host, (data) => data.toString() === 'BACKUP_RESTORE|1');
  viewer.send('BACKUP_RESTORE|1');
  await restoreBackup;

  const agentStatus = waitForMessage(agent, (data) => data.toString() === 'GET_AGENT_STATUS');
  viewer.send('GET_AGENT_STATUS');
  await agentStatus;

  const agentCommand = waitForMessage(agent, (data) => data.toString() === 'RECOVER_HOST');
  viewer.send('RECOVER_HOST');
  await agentCommand;

  const binary = Buffer.from([0xff, 0xd8, 0x01, 0x02, 0xff, 0xd9]);
  const receivedFrame = waitForMessage(viewer, (_data, isBinary) => isBinary);
  host.send(binary);
  assert.deepEqual(Buffer.from((await receivedFrame).data), binary);

  const viewerClosed = new Promise((resolve) => viewer.once('close', (code) => resolve(code)));
  host.send(Buffer.alloc(2 * 1024 * 1024));
  assert.equal(await viewerClosed, 1013);
  await new Promise((resolve) => setTimeout(resolve, 100));

  const viewer2 = await connect('room=test-room&role=viewer');
  t.after(() => viewer2.close());
  const hostStatusRequest = waitForMessage(host, (data) => data.toString() === 'GET_HOST_STATUS');
  viewer2.send('SELECT_HOST|host-1');
  await hostStatusRequest;

  const hostClosed = new Promise((resolve) => host.once('close', resolve));
  host.close();
  await hostClosed;
  const offlineBackupStatus = waitForMessage(agent, (data) => data.toString() === 'GET_BACKUP_STATUS');
  viewer2.send('GET_BACKUP_STATUS');
  await offlineBackupStatus;
});
