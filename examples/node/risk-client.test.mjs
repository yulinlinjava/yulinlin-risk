import test from 'node:test';
import assert from 'node:assert/strict';
import { prepareBatch, RiskClient, RiskHttpError } from './risk-client.mjs';

function event(overrides = {}) {
  return { eventId: 'event-1', userId: 'user-1', action: 'content-view', resourceType: 'video',
    resourceId: 'video-1', route: '/videos/{id}', method: 'GET', occurredAt: Date.now(),
    durationMs: 10, status: 200, ...overrides };
}
function response(data, status = 200) {
  return new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } });
}
function client(fetchImpl, options = {}) {
  return new RiskClient({ baseUrl: 'https://risk.example', apiKey: 'test-key', fetchImpl,
    sleep: async () => {}, ...options });
}

test('sealed batch snapshots caller data and retries an identical body for network/429/503 failures', async () => {
  const original = event();
  const batch = prepareBatch([original]);
  original.action = 'changed-after-sealing';
  assert.equal(batch.events[0].action, 'content-view');
  assert.ok(Object.isFrozen(batch) && Object.isFrozen(batch.events[0]));
  const requests = [];
  const delays = [];
  const reporter = client(async (url, options) => {
    requests.push({ url, options });
    if (requests.length === 1) throw new TypeError('network failure');
    if (requests.length === 2) return response({}, 429);
    if (requests.length === 3) return response({}, 503);
    return response({ accepted: 1, duplicate: true });
  }, { retryDelayMs: 100, sleep: async (ms) => { delays.push(ms); } });
  assert.deepEqual(await reporter.sendBatch(batch), { accepted: 1, duplicate: true });
  assert.deepEqual(delays, [100, 200, 400]);
  assert.ok(requests.every(({ options }) => options.body === batch.body && options.redirect === 'manual'));
  assert.equal(JSON.parse(requests[3].options.body).batchId, batch.batchId);
});

test('401, redirect and malformed acknowledgements are not retried', async () => {
  for (const status of [401, 302, 200]) {
    let calls = 0;
    const reporter = client(async () => {
      calls++;
      return response(status === 200 ? { accepted: 2, duplicate: false } : {}, status);
    });
    await assert.rejects(reporter.sendBatch(prepareBatch([event()])),
      status === 200 ? /acknowledgement/ : (error) => error instanceof RiskHttpError && error.status === status);
    assert.equal(calls, 1);
  }
});

test('retry count is bounded, and missing batchId cannot silently generate a new batch', async () => {
  let calls = 0;
  const reporter = client(async () => { calls++; return response({}, 503); }, { maxRetries: 1 });
  await assert.rejects(reporter.sendBatch(prepareBatch([event()])), /HTTP 503/);
  assert.equal(calls, 2);
  await assert.rejects(reporter.sendBatch({ events: [event()] }), /batchId/);
  assert.equal(calls, 2);
});

test('blacklist snapshot paginates consistently and a version conflict preserves caller cache', async () => {
  let conflict = false;
  const reporter = client(async (url) => {
    const page = Number(new URL(url).searchParams.get('page'));
    const items = page === 1 ? Array.from({ length: 500 }, (_, index) => ({ userId: `user-${index}` }))
      : [{ userId: 'user-500' }];
    return response({ items, page, pageSize: 500, total: 501, version: conflict && page === 2 ? 2 : 1 });
  });
  let cache = await reporter.fetchBlacklistSnapshot();
  assert.equal(cache.size, 501);
  assert.ok(cache.has('user-500'));
  const originalCache = cache;
  conflict = true;
  await assert.rejects(async () => { cache = await reporter.fetchBlacklistSnapshot(); }, /changed/);
  assert.equal(cache, originalCache);
});

test('short or duplicate pages fail; a verified empty snapshot clears cache', async () => {
  const badPages = [
    { items: [], page: 1, pageSize: 500, total: 1, version: 1 },
    { items: [{ userId: 'x' }, { userId: 'x' }], page: 1, pageSize: 500, total: 2, version: 1 },
    { items: [], page: 1, pageSize: 500, total: 200001, version: 1 },
  ];
  for (const page of badPages) {
    await assert.rejects(client(async () => response(page)).fetchBlacklistSnapshot());
  }
  let cache = new Set(['old']);
  cache = await client(async () => response({ items: [], page: 1, pageSize: 500, total: 0, version: 2 }))
    .fetchBlacklistSnapshot();
  assert.equal(cache.size, 0);
});

test('risk lookup encodes identity as one path segment and requests have a timeout', async () => {
  let requested;
  await client(async (url) => { requested = url; return response({ status: 'NO_DATA' }); }).getRisk('u?project=other');
  assert.equal(requested, 'https://risk.example/api/v2/users/u%3Fproject%3Dother/risk');
  const reporter = client((_url, { signal }) => new Promise((_resolve, reject) => {
    signal.addEventListener('abort', () => reject(new Error('aborted')), { once: true });
  }), { timeoutMs: 10, maxRetries: 0 });
  await assert.rejects(reporter.sendBatch(prepareBatch([event()])), /timed out/);
});

test('invalid credentials/URL/events are rejected before sending and oversized responses are bounded', async () => {
  assert.throws(() => client(async () => {}, { baseUrl: 'https://user:pass@risk.example' }), /baseUrl/);
  assert.throws(() => client(async () => {}, { apiKey: 'key\r\nextra' }), /apiKey/);
  assert.throws(() => prepareBatch([event(), event()]), /Duplicate/);
  assert.throws(() => prepareBatch([event({ action: '__OTHER__' })]), /reserved/);
  assert.throws(() => prepareBatch(Array.from({ length: 201 }, (_, i) => event({ eventId: `${i}` }))), /1 to 200/);
  await assert.rejects(client(async () => new Response(' '.repeat(2 * 1024 * 1024 + 1)))
    .getRisk('user-1'), /exceeds/);
});
