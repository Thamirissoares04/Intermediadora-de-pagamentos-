package br.pay;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Webhooks com outbox: o evento é gravado primeiro e entregue depois, com assinatura HMAC-SHA256,
 * retry com backoff exponencial (30s, 60s, 120s...) e "dead letter" após MAX_ATTEMPTS.
 * Entrega é "at-least-once": o lojista deve tratar eventos duplicados pelo campo id.
 */
public final class Webhooks {
    public interface Sender { int send(String url, String body, Map<String, String> headers) throws Exception; }

    public static final class Delivery {
        public final String id, merchantId, type, body;
        public int attempts = 0;
        public Instant nextAttempt = Instant.EPOCH;
        public String state = "PENDING"; // PENDING | DELIVERED | DEAD

        Delivery(String id, String merchantId, String type, String body) {
            this.id = id; this.merchantId = merchantId; this.type = type; this.body = body;
        }
    }

    public static final int MAX_ATTEMPTS = 8;

    private final String secret;
    private final Sender sender;
    private final Map<String, String> endpoints = new HashMap<>();
    private final List<Delivery> outbox = new ArrayList<>();
    private int seq = 0;

    public Webhooks(String secret, Sender sender) {
        this.secret = secret;
        this.sender = sender;
    }

    public synchronized void register(String merchantId, String url) { endpoints.put(merchantId, url); }

    public synchronized void enqueue(String merchantId, String type, Map<String, Object> data) {
        if (!endpoints.containsKey(merchantId)) return;
        String id = "evt_" + (++seq);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("id", id);
        envelope.put("type", type);
        envelope.put("data", data);
        outbox.add(new Delivery(id, merchantId, type, Json.write(envelope)));
    }

    public synchronized List<Delivery> outbox() { return List.copyOf(outbox); }

    /** Tenta entregar o que estiver vencido. A chamada de rede acontece FORA do lock. @return nº de entregas ok. */
    public int deliverPending(Instant now) {
        List<Delivery> due;
        Map<String, String> eps;
        synchronized (this) {
            due = outbox.stream().filter(d -> d.state.equals("PENDING") && !d.nextAttempt.isAfter(now)).toList();
            eps = Map.copyOf(endpoints);
        }
        int ok = 0;
        for (Delivery d : due) {
            boolean success;
            try {
                String ts = Long.toString(now.getEpochSecond());
                Map<String, String> h = new LinkedHashMap<>();
                h.put("Content-Type", "application/json");
                h.put("X-Webhook-Id", d.id);
                h.put("X-Webhook-Timestamp", ts);
                h.put("X-Webhook-Signature", "sha256=" + sign(secret, ts, d.body));
                int code = sender.send(eps.get(d.merchantId), d.body, h);
                success = code >= 200 && code < 300;
            } catch (Exception e) {
                success = false;
            }
            synchronized (this) {
                d.attempts++;
                if (success) { d.state = "DELIVERED"; ok++; }
                else if (d.attempts >= MAX_ATTEMPTS) d.state = "DEAD";
                else d.nextAttempt = now.plusSeconds(30L << (d.attempts - 1));
            }
        }
        return ok;
    }

    public static String sign(String secret, String timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Lado do lojista: confere assinatura (tempo constante) e rejeita timestamps antigos (anti-replay). */
    public static boolean verify(String secret, String timestamp, String body, String signatureHeader,
                                 long nowEpochSeconds, long toleranceSeconds) {
        try {
            if (Math.abs(nowEpochSeconds - Long.parseLong(timestamp)) > toleranceSeconds) return false;
        } catch (NumberFormatException e) {
            return false;
        }
        String expected = "sha256=" + sign(secret, timestamp, body);
        return signatureHeader != null && MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), signatureHeader.getBytes(StandardCharsets.UTF_8));
    }

    public static Sender httpSender() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        return (url, body, headers) -> {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            headers.forEach(b::header);
            return client.send(b.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
        };
    }
}
