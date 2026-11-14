import { randomUUID } from 'node:crypto';
import { setTimeout as delay } from 'node:timers/promises';

const MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

function text(value, name, limit) {
  if (typeof value !== 'string' || !value.trim() || value.length > limit || value.includes('\0')) {
    throw new TypeError(`${name} must be a nonempty string of at most ${limit} characters`);
  }
  return value;
}

function integer(value, name, min, max) {
  if (!Number.isSafeInteger(value) || value < min || value > max) {
    throw new TypeError(`${name} must be an integer between ${min} and ${max}`);
  }
  return value;
}

function optionalText(value, name, limit) {
  return value == null ? null : text(value, name, limit);
}

/** Create once when sealing a batch. Persist body before sending if restart recovery is needed. */
export function prepareBatch(events, batchId = randomUUID()) {
  text(batchId, 'batchId', 128);
  if (!Array.isArray(events) || events.length < 1 || events.length > 200) {
    throw new TypeError('events must contain 1 to 200 events');
  }
  const ids = new Set();
  const copy = events.map((event) => {
    if (!event || typeof event !== 'object') throw new TypeError('Invalid event');
    const id = text(event.eventId, 'eventId', 128);
    if (ids.has(id)) throw new TypeError('Duplicate eventId within batch');
    ids.add(id);
    if (event.action === '__OTHER__') throw new TypeError('action uses a reserved value');
    return Object.freeze({
      eventId: id,
      userId: text(event.userId, 'userId', 128),
      action: text(event.action, 'action', 64),
      resourceType: optionalText(event.resourceType, 'resourceType', 64),
      resourceId: optionalText(event.resourceId, 'resourceId', 256),
      route: text(event.route, 'route', 256),
      method: text(event.method, 'method', 16),
      occurredAt: integer(event.occurredAt, 'occurredAt', 0, Number.MAX_SAFE_INTEGER),
      durationMs: integer(event.durationMs, 'durationMs', 0, 86_400_000),
      status: integer(event.status, 'status', 100, 599),
    });
  });
  Object.freeze(copy);
  const body = JSON.stringify({ batchId, events: copy });
  return Object.freeze({ batchId, events: copy, body });
}

export class RiskHttpError extends Error {
  constructor(status) {
    super(`Risk service returned HTTP ${status}`);
    this.name = 'RiskHttpError';
    this.status = status;
    this.retryable = status === 429 || (status >= 500 && status <= 599);
  }
}

class TransportError extends Error {
  constructor(cause) {
    super('Risk service network request failed or timed out', { cause });
    this.name = 'RiskTransportError';
  }
}

class InvalidResponseError extends Error {
  constructor(message = 'Risk service returned an invalid response') {
    super(message);
    this.name = 'RiskResponseError';
  }
}

async function readJson(response) {
  if (!response.body) throw new InvalidResponseError();
  const reader = response.body.getReader();
  const chunks = [];
  let length = 0;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      length += value.byteLength;
      if (length > MAX_RESPONSE_BYTES) {
        await reader.cancel();
        throw new InvalidResponseError('Risk response exceeds 2 MiB');
      }
      chunks.push(value);
    }
  } catch (error) {
    if (error instanceof InvalidResponseError) throw error;
    throw new TransportError(error);
  } finally {
    reader.releaseLock();
  }
  let body;
  try { body = JSON.parse(Buffer.concat(chunks, length).toString('utf8')); }
  catch { throw new InvalidResponseError(); }
  if (body === null || Array.isArray(body) || typeof body !== 'object') throw new InvalidResponseError();
  return body;
}

/** Lightweight backend HTTP example; each instance belongs to one project API Key. */
export class RiskClient {
  #base;
  #apiKey;
  #fetch;
  #sleep;
  #timeoutMs;
  #maxRetries;
  #retryDelayMs;

  constructor({ baseUrl, apiKey, timeoutMs = 5000, maxRetries = 3, retryDelayMs = 500,
    fetchImpl = globalThis.fetch, sleep = delay } = {}) {
    const url = new URL(baseUrl);
    if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.search || url.hash) {
      throw new TypeError('baseUrl must be HTTP(S), without credentials, query or fragment');
    }
    text(apiKey, 'apiKey', 512);
    if (/[\r\n]/.test(apiKey)) throw new TypeError('Invalid apiKey');
    if (typeof fetchImpl !== 'function' || typeof sleep !== 'function') throw new TypeError('fetch/sleep must be functions');
    this.#base = url.href.replace(/\/+$/, '');
    this.#apiKey = apiKey;
    this.#fetch = fetchImpl;
    this.#sleep = sleep;
    this.#timeoutMs = integer(timeoutMs, 'timeoutMs', 1, 60000);
    this.#maxRetries = integer(maxRetries, 'maxRetries', 0, 10);
    this.#retryDelayMs = integer(retryDelayMs, 'retryDelayMs', 0, 30000);
  }

  prepareBatch(events) { return prepareBatch(events); }

  /** Retry only the same sealed batch. An exhausted attempt may already have committed remotely. */
  async sendBatch(batch) {
    if (!batch || typeof batch !== 'object') throw new TypeError('A sealed batch is required');
    text(batch.batchId, 'batchId', 128); // Never create a new ID while retrying a saved batch.
    const sealed = prepareBatch(batch.events, batch.batchId);
    for (let attempt = 0; ; attempt++) {
      try {
        const ack = await this.#request('POST', '/api/v2/batches', sealed.body);
        if (ack.accepted !== sealed.events.length || typeof ack.duplicate !== 'boolean') {
          throw new InvalidResponseError('Batch acknowledgement does not match the submitted batch');
        }
        return { accepted: ack.accepted, duplicate: ack.duplicate };
      } catch (error) {
        const retryable = error instanceof TransportError || (error instanceof RiskHttpError && error.retryable);
        if (!retryable || attempt >= this.#maxRetries) throw error;
        await this.#sleep(Math.min(30000, this.#retryDelayMs * (2 ** attempt)));
      }
    }
  }

  async getRisk(userId) {
    text(userId, 'userId', 128);
    return this.#request('GET', `/api/v2/users/${encodeURIComponent(userId)}/risk`);
  }

  /** Returns a new complete Set or throws. Caller replaces its cache only after success. */
  async fetchBlacklistSnapshot({ maxUsers = 200000 } = {}) {
    integer(maxUsers, 'maxUsers', 1, 1000000);
    const result = new Set();
    let version;
    let total;
    for (let page = 1; page <= Math.ceil(maxUsers / 500); page++) {
      const data = await this.#request('GET', `/api/blacklist?page=${page}&pageSize=500`);
      if (!Number.isSafeInteger(data.version) || data.version < 0
          || !Number.isSafeInteger(data.total) || data.total < 0 || data.total > maxUsers
          || data.page !== page || data.pageSize !== 500
          || !Array.isArray(data.items) || data.items.length > 500) {
        throw new InvalidResponseError('Invalid blacklist page or capacity exceeded');
      }
      if (page === 1) { version = data.version; total = data.total; }
      if (data.version !== version || data.total !== total) {
        throw new InvalidResponseError('Blacklist changed during pagination; retry from page 1');
      }
      for (const item of data.items) {
        const userId = text(item?.userId, 'blacklist userId', 128);
        if (result.has(userId)) throw new InvalidResponseError('Duplicate blacklist user');
        result.add(userId);
      }
      if (result.size === total) return result;
      if (result.size > total || data.items.length !== 500) {
        throw new InvalidResponseError('Incomplete blacklist snapshot');
      }
    }
    throw new InvalidResponseError('Blacklist pagination exceeded capacity');
  }

  async #request(method, path, body) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.#timeoutMs);
    try {
      let response;
      try {
        response = await this.#fetch(this.#base + path, {
          method,
          headers: { Accept: 'application/json', 'X-API-Key': this.#apiKey,
            ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
          body,
          redirect: 'manual',
          signal: controller.signal,
        });
      } catch (error) { throw new TransportError(error); }
      if (response.status !== 200) {
        // Do not follow redirects or expose response bodies/API Keys in errors.
        try { await response.body?.cancel(); } catch { /* Ignore body disposal failures. */ }
        throw new RiskHttpError(response.status);
      }
      return await readJson(response);
    } finally { clearTimeout(timer); }
  }
}
