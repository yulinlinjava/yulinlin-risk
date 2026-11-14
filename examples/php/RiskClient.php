<?php
declare(strict_types=1);

namespace Yulinlin\RiskExample;

use Closure;
use InvalidArgumentException;
use RuntimeException;

/** Transport example for PHP 8.1+ (64-bit) and cURL; not a published SDK. */
final class RiskClient
{
    private string $baseUrl;
    private string $apiKey;
    private Closure $transport;
    private Closure $sleep;
    private int $maxRetries;
    private int $retryDelayMs;
    private int $connectTimeoutMs;
    private int $requestTimeoutMs;
    private const MAX_RESPONSE_BYTES = 2_000_000;

    /** Inject transport/sleep only for tests or your application's HTTP adapter. */
    public function __construct(
        string $baseUrl,
        string $apiKey,
        array $options = [],
        ?callable $transport = null,
        ?callable $sleep = null
    ) {
        $url = parse_url($baseUrl);
        if (!is_array($url) || !in_array(strtolower($url['scheme'] ?? ''), ['http', 'https'], true)
            || empty($url['host']) || isset($url['user'], $url['pass']) || isset($url['user'])
            || isset($url['pass']) || isset($url['query']) || isset($url['fragment'])
            || preg_match('/[\x00-\x20\x7f]/', $baseUrl)) {
            throw new InvalidArgumentException('Base URL must be HTTP(S) without credentials, query or fragment');
        }
        if (trim($apiKey) === '' || strlen($apiKey) > 512 || preg_match('/[\x00-\x1f\x7f]/', $apiKey)) {
            throw new InvalidArgumentException('API key is missing or invalid');
        }
        if (PHP_INT_SIZE < 8) throw new RuntimeException('A 64-bit PHP runtime is required for millisecond timestamps');
        $this->baseUrl = rtrim($baseUrl, '/');
        $this->apiKey = $apiKey;
        $this->maxRetries = self::integerOption($options, 'maxRetries', 3, 0, 10);
        $this->retryDelayMs = self::integerOption($options, 'retryDelayMs', 500, 1, 30_000);
        $this->connectTimeoutMs = self::integerOption($options, 'connectTimeoutMs', 2_000, 1, 60_000);
        $this->requestTimeoutMs = self::integerOption($options, 'requestTimeoutMs', 5_000, 1, 60_000);
        $this->transport = $transport === null ? Closure::fromCallable([$this, 'curlRequest']) : Closure::fromCallable($transport);
        $this->sleep = $sleep === null ? static fn(int $ms) => usleep($ms * 1000) : Closure::fromCallable($sleep);
    }

    /** Persist this result once when sealing a batch. Reuse it for every retry/job attempt. */
    public function prepareBatch(array $events): array
    {
        $batch = ['batchId' => bin2hex(random_bytes(16)), 'events' => $events];
        self::validateBatch($batch);
        return $batch;
    }

    /** Synchronous worker operation; does not hide a blocking call inside a web request. */
    public function sendBatch(array $batch): array
    {
        self::validateBatch($batch);
        // Serialize exactly once: all network retries preserve the batch ID, order and payload.
        $body = json_encode($batch, JSON_THROW_ON_ERROR | JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
        for ($attempt = 0; ; $attempt++) {
            try {
                $response = $this->request('POST', '/api/v2/batches', $body);
                $status = $response['status'];
                if ($status === 200) {
                    $result = self::decodeObject($response['body']);
                    if (!is_int($result['accepted'] ?? null) || $result['accepted'] !== count($batch['events'])
                        || !is_bool($result['duplicate'] ?? null)) {
                        throw new RuntimeException('Invalid batch acknowledgement; retain the original batch');
                    }
                    return $result;
                }
                $error = new RiskHttpException($status);
                if ($status !== 429 && ($status < 500 || $status > 599)) throw $error;
            } catch (RiskTransportException $transportError) {
                $error = $transportError;
            }
            if ($attempt >= $this->maxRetries) throw $error;
            ($this->sleep)(min(30_000, $this->retryDelayMs * (2 ** $attempt)));
        }
    }

    public function getRisk(string $userId): array
    {
        if (trim($userId) === '') throw new InvalidArgumentException('userId is required');
        return $this->get('/api/v2/users/' . rawurlencode($userId) . '/risk');
    }

    /** Return a complete new snapshot; caller replaces its project-specific cache only on success. */
    public function fetchBlacklistSnapshot(int $maxUsers = 200_000): array
    {
        if ($maxUsers < 1 || $maxUsers > 1_000_000) throw new InvalidArgumentException('Invalid blacklist capacity');
        $expectedVersion = null;
        $expectedTotal = null;
        $users = [];
        $maxPages = max(1, (int) ceil($maxUsers / 500));
        for ($page = 1; $page <= $maxPages; $page++) {
            $data = $this->get('/api/blacklist?page=' . $page . '&pageSize=500');
            if (!is_int($data['page'] ?? null) || $data['page'] !== $page
                || ($data['pageSize'] ?? null) !== 500
                || !is_int($data['version'] ?? null) || $data['version'] < 0
                || !is_int($data['total'] ?? null) || $data['total'] < 0 || $data['total'] > $maxUsers
                || !is_array($data['items'] ?? null) || !array_is_list($data['items']) || count($data['items']) > 500) {
                throw new RuntimeException('Invalid blacklist page or capacity exceeded');
            }
            if ($expectedVersion === null) {
                $expectedVersion = $data['version'];
                $expectedTotal = $data['total'];
            }
            if ($data['version'] !== $expectedVersion || $data['total'] !== $expectedTotal) {
                throw new RuntimeException('Blacklist changed during pagination; keep old cache and restart from page 1');
            }
            foreach ($data['items'] as $item) {
                $id = is_array($item) ? ($item['userId'] ?? null) : null;
                if (!is_string($id) || trim($id) === '' || isset($users['id:' . $id])) {
                    throw new RuntimeException('Invalid or duplicate blacklist user');
                }
                // Prefix avoids PHP's conversion of numeric string keys to integers.
                $users['id:' . $id] = $id;
            }
            if (count($users) === $expectedTotal) return array_values($users);
            if (count($users) > $expectedTotal || count($data['items']) !== 500) {
                throw new RuntimeException('Incomplete blacklist snapshot; keep old cache');
            }
        }
        throw new RuntimeException('Blacklist exceeds page limit');
    }

    private function get(string $path): array
    {
        $response = $this->request('GET', $path, null);
        if ($response['status'] !== 200) throw new RiskHttpException($response['status']);
        return self::decodeObject($response['body']);
    }

    private function request(string $method, string $path, ?string $body): array
    {
        $headers = ['Accept: application/json', 'X-API-Key: ' . $this->apiKey];
        if ($body !== null) $headers[] = 'Content-Type: application/json';
        $response = ($this->transport)($method, $this->baseUrl . $path, $headers, $body);
        if (!is_array($response) || !is_int($response['status'] ?? null) || !is_string($response['body'] ?? null)
            || strlen($response['body']) > self::MAX_RESPONSE_BYTES) {
            throw new RuntimeException('Invalid or oversized HTTP response');
        }
        return $response;
    }

    /** TLS validation remains enabled; redirects are never followed with the API key. */
    private function curlRequest(string $method, string $url, array $headers, ?string $body): array
    {
        if (!function_exists('curl_init')) throw new RuntimeException('Enable the PHP cURL extension');
        $handle = curl_init($url);
        if ($handle === false) throw new RiskTransportException('Could not initialize HTTP transport');
        $received = '';
        $tooLarge = false;
        try {
            curl_setopt_array($handle, [
                CURLOPT_CUSTOMREQUEST => $method,
                CURLOPT_HTTPHEADER => $headers,
                CURLOPT_CONNECTTIMEOUT_MS => $this->connectTimeoutMs,
                CURLOPT_TIMEOUT_MS => $this->requestTimeoutMs,
                CURLOPT_FOLLOWLOCATION => false,
                CURLOPT_PROTOCOLS => CURLPROTO_HTTP | CURLPROTO_HTTPS,
                CURLOPT_WRITEFUNCTION => static function ($unused, string $chunk) use (&$received, &$tooLarge): int {
                    if (strlen($received) + strlen($chunk) > self::MAX_RESPONSE_BYTES) {
                        $tooLarge = true;
                        return 0;
                    }
                    $received .= $chunk;
                    return strlen($chunk);
                },
            ]);
            if ($body !== null) curl_setopt($handle, CURLOPT_POSTFIELDS, $body);
            $ok = curl_exec($handle);
            if ($tooLarge) throw new RuntimeException('Risk response exceeds size limit');
            if ($ok === false) throw new RiskTransportException('Risk HTTP transport failed (cURL ' . curl_errno($handle) . ')');
            return ['status' => (int) curl_getinfo($handle, CURLINFO_RESPONSE_CODE), 'body' => $received];
        } finally {
            curl_close($handle);
        }
    }

    private static function decodeObject(string $body): array
    {
        $decoded = json_decode($body, true, 512, JSON_THROW_ON_ERROR);
        if (!is_array($decoded) || !str_starts_with(ltrim($body), '{')) throw new RuntimeException('Expected a JSON object');
        return $decoded;
    }

    private static function validateBatch(array $batch): void
    {
        $id = $batch['batchId'] ?? null;
        $events = $batch['events'] ?? null;
        if (!is_string($id) || trim($id) === '' || strlen($id) > 128 || str_contains($id, "\0")
            || !is_array($events) || !array_is_list($events) || count($events) < 1 || count($events) > 200) {
            throw new InvalidArgumentException('Expected batchId and 1..200 events');
        }
        $ids = [];
        foreach ($events as $event) {
            $eventId = is_array($event) ? ($event['eventId'] ?? null) : null;
            if (!is_array($event) || array_is_list($event) || !is_string($eventId) || trim($eventId) === ''
                || isset($ids['id:' . $eventId])) {
                throw new InvalidArgumentException('Events must be JSON objects with unique eventId values');
            }
            $ids['id:' . $eventId] = true;
        }
    }

    private static function integerOption(array $options, string $name, int $default, int $min, int $max): int
    {
        $value = $options[$name] ?? $default;
        if (!is_int($value) || $value < $min || $value > $max) throw new InvalidArgumentException('Invalid ' . $name);
        return $value;
    }
}

final class RiskHttpException extends RuntimeException
{
    public function __construct(public readonly int $status)
    {
        parent::__construct('Risk service returned HTTP ' . $status . '; retain the original batch when retrying');
    }
}

final class RiskTransportException extends RuntimeException {}
