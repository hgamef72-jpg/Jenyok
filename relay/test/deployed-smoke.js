'use strict';

const WebSocket = require('ws');
const endpoint = process.env.JENYOK_RELAY_URL || 'wss://jenyok-relay-rebuilt.onrender.com/connect';
const suffix = Date.now() + '-' + process.pid;
const room = 'jenyok-smoke-' + suffix;
const hostId = 'smoke-host-' + suffix;

function connect(role, id) {
  const url = new URL(endpoint);
  url.searchParams.set('room', room);
  url.searchParams.set('role', role);
  if (id) url.searchParams.set('hostId', id);
  return new Promise((resolve, reject) => {
    const ws = new WebSocket(url);
    const timer = setTimeout(() => reject(new Error('Timed out connecting ' + role)), 10000);
    ws.once('open', () => { clearTimeout(timer); resolve(ws); });
    ws.once('error', (error) => { clearTimeout(timer); reject(error); });
  });
}

function waitFor(ws, predicate, label, timeout = 7000) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      ws.off('message', onMessage);
      reject(new Error('Timed out waiting for ' + label));
    }, timeout);
    function onMessage(data, binary) {
      if (!predicate(data.toString(), binary)) return;
      clearTimeout(timer);
      ws.off('message', onMessage);
      resolve(data.toString());
    }
    ws.on('message', onMessage);
  });
}

function close(ws) {
  if (!ws || ws.readyState === WebSocket.CLOSED) return Promise.resolve();
  return new Promise((resolve) => {
    ws.once('close', resolve);
    ws.close();
    setTimeout(() => { if (ws.readyState !== WebSocket.CLOSED) ws.terminate(); }, 1000).unref();
  });
}

(async () => {
  let host, agent, viewer;
  try {
    host = await connect('host', hostId);
    agent = await connect('agent', hostId);
    host.send('HOST_IDENTITY|Relay smoke test|Roblox12311.exe');
    host.send('SCREEN_COUNT|1');
    agent.send('HOST_AGENT_READY|1');
    await close(host); // Recovery must still work while the Host process is offline.

    viewer = await connect('viewer');
    const hostInfoWait = waitFor(viewer, (m) => m.startsWith('HOST_INFO|' + hostId + '|'), 'offline Host in list');
    viewer.send('GET_HOST_LIST');
    const hostInfo = await hostInfoWait;
    if (!hostInfo.endsWith('|0|0|1')) throw new Error('Unexpected Host/Agent status: ' + hostInfo);

    const selectedWait = waitFor(viewer, (m) => m === 'HOST_SELECTED|' + hostId, 'HOST_SELECTED');
    const readyWait = waitFor(viewer, (m) => m === 'HOST_AGENT_READY|1', 'Recovery Agent ready');
    viewer.send('SELECT_HOST|' + hostId);
    await selectedWait;
    await readyWait;

    const recoveryCommand = waitFor(agent, (m) => m === 'RECOVER_HOST', 'RECOVER_HOST routed to Agent');
    viewer.send('RECOVER_HOST');
    await recoveryCommand;

    const acceptedWait = waitFor(viewer, (m) => m === 'RECOVERY_ACCEPTED|1', 'Recovery acceptance returned to Viewer');
    agent.send('RECOVERY_ACCEPTED|1');
    await acceptedWait;

    process.stdout.write('PASS: deployed Relay host selection and offline-Host Recovery routing\n');
  } finally {
    await Promise.all([close(viewer), close(agent), close(host)]);
  }
})().catch((error) => {
  process.stderr.write(String(error.stack || error) + '\n');
  process.exitCode = 1;
});
