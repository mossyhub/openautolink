import test from 'node:test';
import assert from 'node:assert/strict';

class FakeWorker {
  listeners = new Map(); sent = []; terminated = 0;
  addEventListener(type, fn) { if (!this.listeners.has(type)) this.listeners.set(type, new Set()); this.listeners.get(type).add(fn); }
  removeEventListener(type, fn) { this.listeners.get(type)?.delete(fn); }
  postMessage(message, transfer = []) { this.sent.push({ message: structuredClone(message), transfer }); }
  terminate() { this.terminated++; }
  emit(type, data) { for (const fn of [...(this.listeners.get(type) || [])]) fn({ data, preventDefault() {} }); }
  reply(type, fields = {}) { this.emit('message', { jobId: this.sent[0].message.jobId, type, ...fields }); }
  get listenerCount() { return [...this.listeners.values()].reduce((sum, set) => sum + set.size, 0); }
}
const input = () => ({ templateBytes: new Uint8Array([1, 2]), descriptor: {}, identity: {
  format: 'oal-web-upload-identity', formatVersion: 1, applicationId: 'com.example.test',
  privateKeyPem: 'PRIVATE_SENTINEL', certificatePem: 'public-cert', versionCodeHighWaterMark: 0,
}, versionCode: 1, backupPassword: 'PASSWORD_SENTINEL' });
const resultFor = value => ({ aabBytes: new Uint8Array([3]), backupBytes: new Uint8Array([4]),
  identity: { ...value.identity, versionCodeHighWaterMark: value.versionCode } });
const client = () => import('../core/worker-client.mjs');

test('every settlement removes the abort listener and clears the exact deadline timer', async () => {
  const { runBuildInWorker } = await client();
  const { getEventListeners } = await import('node:events');
  const set = globalThis.setTimeout, clear = globalThis.clearTimeout;
  const timers = new Map(); let next = 0;
  globalThis.setTimeout = fn => { const id = ++next; timers.set(id, fn); return id; };
  globalThis.clearTimeout = id => timers.delete(id);
  try {
    for (const outcome of ['success', 'abort', 'error', 'timeout']) {
      const controller = new AbortController(), worker = new FakeWorker();
      const promise = runBuildInWorker(input(), { signal: controller.signal, workerFactory: () => worker });
      const checked = outcome === 'success' ? promise : assert.rejects(promise);
      assert.equal(timers.size, 1);
      assert.equal(getEventListeners(controller.signal, 'abort').length, 1);
      if (outcome === 'success') worker.reply('result', { result: resultFor(input()) });
      if (outcome === 'abort') controller.abort();
      if (outcome === 'error') worker.emit('error', 'PRIVATE_SENTINEL');
      if (outcome === 'timeout') [...timers.values()][0]();
      await checked;
      assert.equal(timers.size, 0);
      assert.equal(getEventListeners(controller.signal, 'abort').length, 0);
      assert.equal(worker.listenerCount, 0);
      assert.equal(worker.terminated, 1);
    }
  } finally { globalThis.setTimeout = set; globalThis.clearTimeout = clear; }
});

test('a reused worker factory is rejected without disrupting the first invocation', async () => {
  const { runBuildInWorker } = await client();
  const worker = new FakeWorker(), options = { workerFactory: () => worker, timeoutMs: 100 };
  const first = runBuildInWorker(input(), options);
  const second = runBuildInWorker(input(), options);
  const check = assert.rejects(second, /fresh worker/i);
  assert.equal(worker.sent.length, 1);
  assert.equal(worker.terminated, 0);
  worker.reply('result', { result: resultFor(input()) });
  await first; await check;
  await assert.rejects(runBuildInWorker(input(), options), /fresh worker/i);
});

test('qualification script has a sanitized configuration failure without a fixture', async () => {
  const { spawnSync } = await import('node:child_process');
  const result = spawnSync(process.execPath, ['scripts/worker-qualify.mjs'], {
    cwd: new URL('..', import.meta.url), encoding: 'utf8', env: { ...process.env, OAL_WEB_TEMPLATE: '', OAL_WEB_TOOLS: '' },
  });
  assert.equal(result.status, 1);
  assert.deepEqual(JSON.parse(result.stderr), { status: 'failed', phase: 'configuration' });
});

test('browser worker entry builds once and transfers only output byte buffers', async () => {
  const { installBuildWorker } = await import('../build.worker.mjs');
  const scope = new FakeWorker(), original = input(), result = resultFor(original);
  let builds = 0, finish;
  installBuildWorker(scope, value => { assert.deepEqual(value, original); builds++; return new Promise(resolve => { finish = resolve; }); });
  scope.emit('message', { type: 'build', jobId: 'first', input: original });
  scope.emit('message', { type: 'build', jobId: 'second', input: original });
  assert.equal(builds, 1);
  assert.ok(scope.sent.some(({ message }) => message.jobId === 'second' && message.type === 'error'));
  finish(result);
  await new Promise(resolve => setImmediate(resolve));
  const sent = scope.sent.find(({ message }) => message.type === 'result');
  assert.deepEqual(sent.message, { type: 'result', jobId: 'first', result });
  assert.deepEqual(sent.transfer, [result.aabBytes.buffer, result.backupBytes.buffer]);
  scope.emit('message', { type: 'build', jobId: 'late', input: original });
  assert.equal(builds, 1);
});

test('worker entry reports safe errors for malformed requests and pipeline exceptions', async () => {
  const { installBuildWorker } = await import('../build.worker.mjs');
  for (const request of [null, { type: 'unknown', jobId: 'test' }, { type: 'build', jobId: 'test', input: input() }]) {
    const scope = new FakeWorker();
    installBuildWorker(scope, async () => { throw Error('PRIVATE_SENTINEL PASSWORD_SENTINEL'); });
    scope.emit('message', request);
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(scope.sent.length, request?.type === 'build' ? 2 : 1);
    const error = scope.sent.at(-1).message;
    assert.equal(error.type, 'error');
    assert.ok(!JSON.stringify(scope.sent).includes('SENTINEL'));
  }
});

test('malformed responses reject rather than leaking raw worker content', async () => {
  const { runBuildInWorker } = await client();
  for (const data of [null, {}, { type: 'mystery' }, { type: 'result', result: {} },
    { type: 'result', result: { ...resultFor(input()), aabBytes: new Uint8Array() } },
    { type: 'result', result: { ...resultFor(input()), identity: null } },
    { type: 'progress', phase: 'PRIVATE_SENTINEL' }]) {
    const worker = new FakeWorker();
    const promise = runBuildInWorker(input(), { workerFactory: () => worker, timeoutMs: 100 });
    const check = assert.rejects(promise, error => error.name === 'Error' && !String(error).includes('SENTINEL'));
    if (data === null) worker.emit('message', null);
    else worker.reply(data.type, data);
    await check;
    assert.equal(worker.listenerCount, 0);
  }
});

test('only whitelisted progress reaches callbacks; callback exceptions do not strand jobs', async () => {
  const { runBuildInWorker } = await client();
  const worker = new FakeWorker(), progress = [];
  const promise = runBuildInWorker(input(), { workerFactory: () => worker, onProgress: value => {
    progress.push(value); throw Error('PRIVATE_SENTINEL');
  } });
  worker.reply('progress', { phase: 'building', privateKey: 'PRIVATE_SENTINEL' });
  worker.reply('result', { result: resultFor(input()) });
  await promise;
  assert.deepEqual(progress, [{ phase: 'building' }]);
});

test('independent calls ignore stale jobs and late callbacks after settlement', async () => {
  const { runBuildInWorker } = await client();
  const workers = [new FakeWorker(), new FakeWorker()], events = [];
  const promises = workers.map(worker => runBuildInWorker(input(), { workerFactory: () => worker, onProgress: p => events.push(p) }));
  const late = [...workers[0].listeners.get('message')][0];
  assert.notEqual(workers[0].sent[0].message.jobId, workers[1].sent[0].message.jobId);
  workers[0].emit('message', { jobId: workers[1].sent[0].message.jobId, type: 'result', result: {} });
  assert.equal(workers[0].terminated, 0);
  for (const worker of workers) worker.reply('result', { result: resultFor(input()) });
  await Promise.all(promises);
  late({ data: { jobId: workers[0].sent[0].message.jobId, type: 'progress', phase: 'building' } });
  assert.deepEqual(events, []);
  assert.ok(workers.every(w => w.terminated === 1 && w.listenerCount === 0));
});

test('failed worker lifecycle is bounded, sanitized and cleaned up', async () => {
  const { runBuildInWorker } = await client();
  for (const failure of ['timeout', 'error', 'messageerror', 'remote', 'factory', 'post']) {
    const worker = new FakeWorker();
    if (failure === 'post') worker.postMessage = () => { throw Error('PRIVATE_SENTINEL'); };
    const promise = runBuildInWorker(input(), { timeoutMs: 10, workerFactory: () => {
      if (failure === 'factory') throw Error('PRIVATE_SENTINEL');
      return worker;
    } });
    const rejected = assert.rejects(promise, error => !String(error).includes('SENTINEL') &&
      error.name === (failure === 'timeout' ? 'TimeoutError' : 'Error'));
    if (['error', 'messageerror'].includes(failure)) worker.emit(failure, 'PRIVATE_SENTINEL');
    if (failure === 'remote') worker.reply('error', { message: 'PRIVATE_SENTINEL', code: 'PRIVATE_SENTINEL' });
    await rejected;
    assert.equal(worker.terminated, failure === 'factory' ? 0 : 1);
    assert.equal(worker.listenerCount, 0);
  }
});

test('invalid or unbounded timeouts reject before creating a worker', async () => {
  const { runBuildInWorker } = await client();
  for (const timeoutMs of [0, -1, Infinity, NaN, 0.5, 300001, '10']) {
    let created = false;
    await assert.rejects(runBuildInWorker(input(), { timeoutMs, workerFactory: () => { created = true; return new FakeWorker(); } }));
    assert.equal(created, false);
  }
});

test('abort before start creates no worker; abort during work terminates immediately with safe error', async () => {
  const { runBuildInWorker } = await client();
  for (const before of [true, false]) {
    const controller = new AbortController(), worker = new FakeWorker();
    let created = 0;
    if (before) controller.abort('PRIVATE_SENTINEL');
    const promise = runBuildInWorker(input(), { signal: controller.signal, workerFactory: () => { created++; return worker; } });
    if (!before) controller.abort('PRIVATE_SENTINEL');
    assert.equal(created, before ? 0 : 1);
    assert.equal(worker.terminated, before ? 0 : 1);
    await assert.rejects(promise, error => error.name === 'AbortError' && !String(error).includes('SENTINEL'));
    assert.equal(worker.listenerCount, 0);
  }
});

test('one build returns bytes and updated identity without detaching input, then cleans up', async () => {
  const { runBuildInWorker } = await client();
  const worker = new FakeWorker(), original = input();
  original.carplay = { keyBytes: new Uint8Array([5, 6]), certificateBytes: new Uint8Array([7, 8]) };
  const promise = runBuildInWorker(original, { workerFactory: () => worker });
  assert.equal(worker.sent[0].message.type, 'build');
  assert.deepEqual(worker.sent[0].message.input, original);
  assert.equal(worker.sent[0].transfer.length, 0);
  worker.reply('result', { result: resultFor(original) });
  assert.deepEqual(await promise, resultFor(original));
  assert.equal(original.templateBytes.length, 2);
  assert.equal(original.identity.versionCodeHighWaterMark, 0);
  assert.equal(worker.terminated, 1);
  assert.equal(worker.listenerCount, 0);
});
