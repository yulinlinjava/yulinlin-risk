<?php
declare(strict_types=1);

require __DIR__ . '/RiskClient.php';

use Yulinlin\RiskExample\RiskClient;

// Invoke from a CLI worker/job. PHP-FPM request-local objects are not a cross-request batch queue.
// The batch file must already contain a stable batchId and original event timestamps.
if (PHP_SAPI !== 'cli') { http_response_code(400); exit('CLI only'); }
try {
    if (!isset($argv[1]) || !is_file($argv[1])) {
        throw new InvalidArgumentException('Usage: php example.php batch.json [userId]');
    }
    $batch = json_decode(file_get_contents($argv[1]), true, 512, JSON_THROW_ON_ERROR);
    if (!is_array($batch)) throw new InvalidArgumentException('Batch file must contain a JSON object');
    $client = new RiskClient(getenv('RISK_BASE_URL') ?: 'http://127.0.0.1:8080', getenv('RISK_API_KEY') ?: '');
    echo json_encode($client->sendBatch($batch), JSON_THROW_ON_ERROR | JSON_UNESCAPED_UNICODE), PHP_EOL;
    if (isset($argv[2])) {
        echo json_encode($client->getRisk($argv[2]), JSON_THROW_ON_ERROR | JSON_UNESCAPED_UNICODE), PHP_EOL;
    }
} catch (Throwable $error) {
    fwrite(STDERR, $error->getMessage() . PHP_EOL);
    exit(1);
}
