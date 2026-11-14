package com.yulinlin.risk.sdk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

public final class HttpRiskTransport implements RiskTransport {
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();
    private final String base;
    private final String apiKey;
    private final Duration timeout;

    public HttpRiskTransport(RiskClientProperties config) {
        config.validate();
        base = config.getBaseUrl().toString().replaceAll("/+$", "");
        apiKey = config.getApiKey();
        timeout = config.getRequestTimeout();
        http = HttpClient.newBuilder().connectTimeout(config.getConnectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override public SendResult send(RiskBatch batch) throws IOException, InterruptedException {
        var response = http.send(request("/api/v2/batches").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(batch))).build(),
                info -> new LimitedResponseSubscriber(2_000_000));
        if (response.statusCode() < 200 || response.statusCode() >= 300) return new SendResult(response.statusCode(), 0);
        var body = read(response.body());
        if (!body.path("accepted").canConvertToInt()) throw new IOException("Risk batch response is invalid");
        return new SendResult(response.statusCode(), body.path("accepted").intValue());
    }

    @Override public BlacklistPage blacklist(int page, int pageSize) throws IOException, InterruptedException {
        var body = get("/api/blacklist?page=" + page + "&pageSize=" + pageSize);
        if (!body.path("items").isArray() || !body.path("version").canConvertToLong()
                || !body.path("total").canConvertToLong() || !body.path("page").canConvertToInt()
                || !body.path("pageSize").canConvertToInt()) throw new IOException("Risk blacklist response is invalid");
        var ids = new ArrayList<String>();
        for (var item : body.path("items")) {
            if (!item.path("userId").isTextual() || item.path("userId").asText().isBlank()) {
                throw new IOException("Risk blacklist user is invalid");
            }
            ids.add(item.path("userId").asText());
        }
        return new BlacklistPage(ids, body.path("page").intValue(), body.path("pageSize").intValue(),
                body.path("total").longValue(), body.path("version").longValue());
    }

    @Override public JsonNode risk(String userId) throws IOException, InterruptedException {
        if (userId == null || userId.isBlank() || userId.length() > 128) throw new IllegalArgumentException("Invalid userId");
        return get("/api/v2/users/" + URLEncoder.encode(userId, StandardCharsets.UTF_8).replace("+", "%20") + "/risk");
    }

    private JsonNode get(String path) throws IOException, InterruptedException {
        var response = http.send(request(path).GET().build(), info -> new LimitedResponseSubscriber(2_000_000));
        if (response.statusCode() != 200) throw new IOException("Risk service returned HTTP " + response.statusCode());
        return read(response.body());
    }

    private JsonNode read(byte[] bytes) throws IOException {
        if (bytes.length > 2_000_000) throw new IOException("Risk response is too large");
        var body = json.readTree(bytes);
        if (body == null || !body.isObject()) throw new IOException("Risk response is invalid");
        return body;
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(base + path)).timeout(timeout)
                .header("X-API-Key", apiKey).header("Accept", "application/json");
    }

    /** Cancels oversized bodies while streaming; the request timeout still covers the full body. */
    static final class LimitedResponseSubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final int limit;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private Flow.Subscription subscription;

        LimitedResponseSubscriber(int limit) { this.limit = limit; }
        @Override public CompletionStage<byte[]> getBody() { return body; }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            if (body.isDone()) return;
            for (var buffer : buffers) {
                if ((long) bytes.size() + buffer.remaining() > limit) {
                    subscription.cancel();
                    body.completeExceptionally(new IOException("Risk response is too large"));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable throwable) { body.completeExceptionally(throwable); }
        @Override public void onComplete() { body.complete(bytes.toByteArray()); }
    }
}
