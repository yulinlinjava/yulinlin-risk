import { readFile, writeFile } from 'node:fs/promises';
import { prepareBatch, RiskClient } from './risk-client.mjs';

// Run from a background worker/CLI. Avoid awaiting network retries in a business request handler.
async function main() {
  const [command, path, extra] = process.argv.slice(2);
  if (command === 'prepare' && path && extra) {
    const events = JSON.parse(await readFile(path, 'utf8'));
    const batch = prepareBatch(events);
    // Do not overwrite an unsent batch: its stable ID must survive process restarts.
    await writeFile(extra, batch.body + '\n', { flag: 'wx' });
    console.log(JSON.stringify({ batchId: batch.batchId, events: batch.events.length, file: extra }));
    return;
  }
  if (command !== 'send' || !path) {
    throw new Error('Usage: node example.mjs prepare events.json batch.json | send batch.json [userId]');
  }
  const client = new RiskClient({
    baseUrl: process.env.RISK_BASE_URL ?? 'http://127.0.0.1:8080',
    apiKey: process.env.RISK_API_KEY,
  });
  const batch = JSON.parse(await readFile(path, 'utf8'));
  const result = await client.sendBatch(batch);
  console.log(JSON.stringify({ batchId: batch.batchId, ...result }));
  // Keep batch.json after failures and retry this exact file; do not regenerate or split it.
  if (extra) console.log(JSON.stringify(await client.getRisk(extra)));
}

main().catch((error) => {
  console.error(error.message); // Do not dump request headers, environment variables or API Keys.
  process.exitCode = 1;
});
