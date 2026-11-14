<?php
declare(strict_types=1);

require __DIR__ . '/RiskClient.php';

use Yulinlin\RiskExample\RiskClient;
use Yulinlin\RiskExample\RiskHttpException;
use Yulinlin\RiskExample\RiskTransportException;

function check(bool $condition, string $message): void {
    if (!$condition) throw new RuntimeException($message);
}
function fails(callable $operation, string $contains): void {
    try { $operation(); } catch (Throwable $error) {
        check(str_contains($error->getMessage(), $contains), 'Unexpected error: ' . $error->getMessage());
        return;
    }
    throw new RuntimeException('Expected failure: ' . $contains);
}
function response(array $body, int $status = 200): array {
    return ['status' => $status, 'body' => json_encode($body, JSON_THROW_ON_ERROR)];
}
function event(): array {
    return ['eventId' => 'event-1', 'userId' => 'user-1', 'action' => 'content-view',
        'resourceType' => 'video', 'resourceId' => 'v1', 'route' => '/videos/{id}', 'method' => 'GET',
        'occurredAt' => (int) floor(microtime(true) * 1000), 'durationMs' => 12, 'status' => 200];
}

$payloads = []; $sleeps = [];
$client = new RiskClient('http://127.0.0.1:8080', 'test-key', [],
    function ($method, $url, $headers, $body) use (&$payloads): array {
        check($method === 'POST' && str_ends_with($url, '/api/v2/batches'), 'Wrong request');
        $payloads[] = $body;
        if (count($payloads) === 1) throw new RiskTransportException('simulated timeout after commit');
        if (count($payloads) === 2) return response([], 503);
        return response(['accepted' => 1, 'duplicate' => true]);
    }, function (int $ms) use (&$sleeps): void { $sleeps[] = $ms; });
$batch = $client->prepareBatch([event()]);
check($client->sendBatch($batch)['duplicate'] === true, 'Duplicate ACK not handled');
check(count($payloads) === 3 && count(array_unique($payloads)) === 1, 'Retry changed batch payload');
check($sleeps === [500, 1000], 'Retry backoff differs');

$attempts = 0;
$unauthorized = new RiskClient('https://risk.example.invalid', 'test-key', [],
    function () use (&$attempts): array { $attempts++; return response([], 401); }, static function (): void {});
fails(fn() => $unauthorized->sendBatch($batch), 'HTTP 401');
check($attempts === 1, '401 retried');

$pageOne = array_map(static fn(int $i): array => ['userId' => 'u-' . $i], range(1, 500));
$calls = 0;
$good = new RiskClient('https://risk.example.invalid', 'test-key', [],
    function ($method, $url) use (&$calls, $pageOne): array {
        $calls++;
        return response(['items' => $calls === 1 ? $pageOne : [['userId' => 'u-501']],
            'page' => $calls, 'pageSize' => 500, 'total' => 501, 'version' => 10]);
    });
check(count($good->fetchBlacklistSnapshot()) === 501 && $calls === 2, 'Pagination failed');

$calls = 0; $oldCache = ['previous-user'];
$changed = new RiskClient('https://risk.example.invalid', 'test-key', [],
    function () use (&$calls, $pageOne): array {
        $calls++;
        return response(['items' => $calls === 1 ? $pageOne : [['userId' => 'u-501']],
            'page' => $calls, 'pageSize' => 500, 'total' => 501, 'version' => $calls === 1 ? 10 : 11]);
    });
fails(function () use ($changed, &$oldCache): void { $oldCache = $changed->fetchBlacklistSnapshot(); }, 'changed during pagination');
check($oldCache === ['previous-user'], 'Failure replaced old cache');

$empty = new RiskClient('https://risk.example.invalid', 'test-key', [],
    static fn(): array => response(['items' => [], 'page' => 1, 'pageSize' => 500, 'total' => 0, 'version' => 12]));
check($empty->fetchBlacklistSnapshot() === [], 'Valid empty snapshot not returned');
$incomplete = new RiskClient('https://risk.example.invalid', 'test-key', [],
    static fn(): array => response(['items' => [], 'page' => 1, 'pageSize' => 500, 'total' => 2, 'version' => 12]));
fails(fn() => $incomplete->fetchBlacklistSnapshot(), 'Incomplete');
fails(fn() => $client->prepareBatch([]), '1..200');
fails(fn() => $client->prepareBatch([event(), event()]), 'unique eventId');
fails(fn() => new RiskClient('https://user:pass@example.invalid', 'key'), 'Base URL');

$queried = '';
$lookup = new RiskClient('https://risk.example.invalid', 'test-key', [],
    function ($method, $url) use (&$queried): array { $queried = $url; return response(['status' => 'OBSERVING']); });
$lookup->getRisk('user 1');
check(str_ends_with($queried, '/users/user%201/risk'), 'User path not encoded');
echo "PHP_CLIENT_TESTS_OK stableRetry=true no401Retry=true pagination=true versionConflict=true emptySnapshot=true validation=true\n";
