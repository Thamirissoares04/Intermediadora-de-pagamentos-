package br.pay;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** API HTTP/JSON sobre o servidor embutido do JDK (zero dependências). */
public final class Api {
    private static final int MAX_BODY = 64 * 1024;

    private final PaymentService svc;
    private final Acquirer acquirer;
    private final Ledger ledger;
    private final byte[] expectedAuth;
    private final String allowedOrigin; // origem exata do app (CORS); null = CORS desligado

    private Api(PaymentService svc, Acquirer acquirer, Ledger ledger, String apiKey, String allowedOrigin) {
        this.svc = svc;
        this.acquirer = acquirer;
        this.ledger = ledger;
        this.expectedAuth = ("Bearer " + apiKey).getBytes(StandardCharsets.UTF_8);
        this.allowedOrigin = allowedOrigin;
    }

    public static HttpServer start(int port, PaymentService svc, Acquirer acquirer, Ledger ledger, String apiKey) throws IOException {
        return start("127.0.0.1", port, svc, acquirer, ledger, apiKey, null);
    }

    public static HttpServer start(String host, int port, PaymentService svc, Acquirer acquirer, Ledger ledger,
                                   String apiKey, String allowedOrigin) throws IOException {
        Api api = new Api(svc, acquirer, ledger, apiKey, allowedOrigin);
        HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
        server.createContext("/", ex -> {
            try {
                if (api.cors(ex)) return;
                api.route(ex);
            } catch (ApiException e) {
                send(ex, e.status, Map.of("error", Map.of("code", e.code, "message", String.valueOf(e.getMessage()))));
            } catch (Exception e) {
                e.printStackTrace();
                send(ex, 500, Map.of("error", Map.of("code", "internal_error", "message", "erro interno")));
            } finally {
                ex.close();
            }
        });
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();
        return server;
    }

    /** CORS: só a origem exata configurada recebe permissão. @return true se já respondeu (preflight OPTIONS). */
    private boolean cors(HttpExchange ex) throws IOException {
        String origin = ex.getRequestHeaders().getFirst("Origin");
        boolean allowed = allowedOrigin != null && origin != null && origin.equals(allowedOrigin);
        ex.getResponseHeaders().add("Vary", "Origin");
        if (allowed) ex.getResponseHeaders().set("Access-Control-Allow-Origin", origin);
        if (!ex.getRequestMethod().equals("OPTIONS")) return false;
        if (allowed) {
            ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
            ex.getResponseHeaders().set("Access-Control-Allow-Headers", "Authorization, Content-Type, Idempotency-Key");
            ex.getResponseHeaders().set("Access-Control-Max-Age", "600");
            ex.sendResponseHeaders(204, -1);
        } else {
            ex.sendResponseHeaders(403, -1);
        }
        return true;
    }

    private void route(HttpExchange ex) throws IOException {
        String m = ex.getRequestMethod();
        String path = ex.getRequestURI().getPath();
        if (m.equals("GET") && path.equals("/health")) { send(ex, 200, Map.of("status", "ok")); return; }

        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !MessageDigest.isEqual(auth.getBytes(StandardCharsets.UTF_8), expectedAuth))
            throw new ApiException(401, "unauthorized", "API key inválida");

        String[] s = path.split("/"); // "/v1/payments/pay_1/refund" -> ["", "v1", "payments", "pay_1", "refund"]
        int n = s.length;
        if (n < 3 || !s[1].equals("v1")) throw notFound();

        if (m.equals("POST") && n == 3 && s[2].equals("merchants")) {
            Map<String, Object> b = body(ex);
            Models.Merchant mer = svc.registerMerchant(str(b, "id"), str(b, "name"), integer(b, "feeBps", 0),
                    lng(b, "fixedFeeCents", 0), b.get("webhookUrl") instanceof String u ? u : null);
            send(ex, 201, mer);
        } else if (m.equals("POST") && n == 3 && s[2].equals("payments")) {
            Models.Payment p = svc.charge(chargeRequest(ex, body(ex)));
            send(ex, p.status() == Models.PaymentStatus.DECLINED ? 402 : 201, p);
        } else if (m.equals("GET") && n == 4 && s[2].equals("payments")) {
            send(ex, 200, Map.of("payment", svc.payment(s[3]), "receivables", svc.receivablesOf(s[3])));
        } else if (m.equals("POST") && n == 5 && s[2].equals("payments") && s[4].equals("refund")) {
            send(ex, 200, svc.refund(s[3]));
        } else if (m.equals("GET") && n == 5 && s[2].equals("merchants") && s[4].equals("balance")) {
            send(ex, 200, svc.balance(s[3]));
        } else if (m.equals("POST") && n == 4 && s[2].equals("settlements") && s[3].equals("run")) {
            Map<String, Object> b = body(ex);
            LocalDate date;
            try { date = b.get("date") instanceof String d ? LocalDate.parse(d) : LocalDate.now(); }
            catch (DateTimeParseException e) { throw bad("invalid_date", "date deve estar no formato AAAA-MM-DD"); }
            send(ex, 200, Map.of("asOf", date, "settledByMerchant", svc.settleDue(date)));
        } else if (m.equals("GET") && n == 3 && s[2].equals("reconciliation")) {
            send(ex, 200, Reconciliation.run(svc.payments(), acquirer.report()));
        } else if (m.equals("GET") && n == 4 && s[2].equals("ledger") && s[3].equals("integrity")) {
            long sum = ledger.totalSum();
            send(ex, 200, Map.of("sum", sum, "ok", sum == 0, "entries", ledger.entries().size()));
        } else {
            throw notFound();
        }
    }

    private Models.ChargeRequest chargeRequest(HttpExchange ex, Map<String, Object> b) {
        Models.Method method;
        try { method = Models.Method.valueOf(str(b, "method").toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException e) { throw bad("invalid_method", "method deve ser PIX, CARD ou BOLETO"); }

        List<Models.SplitRule> splits = new ArrayList<>();
        Object sp = b.get("splits");
        if (sp != null) {
            if (!(sp instanceof List<?> list)) throw bad("invalid_split", "splits deve ser uma lista");
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> mm)) throw bad("invalid_split", "cada split deve ser um objeto");
                splits.add(new Models.SplitRule(str(mm, "merchantId"), integer(mm, "bps", -1)));
            }
        }
        return new Models.ChargeRequest(ex.getRequestHeaders().getFirst("Idempotency-Key"), str(b, "merchantId"),
                lng(b, "amount", -1), method, integer(b, "installments", 1),
                b.get("token") instanceof String t ? t : null, splits);
    }

    // ---------- helpers ----------

    private static Map<String, Object> body(HttpExchange ex) throws IOException {
        byte[] raw = ex.getRequestBody().readNBytes(MAX_BODY + 1);
        if (raw.length > MAX_BODY) throw new ApiException(413, "payload_too_large", "corpo maior que 64KB");
        try { return Json.parseObject(new String(raw, StandardCharsets.UTF_8)); }
        catch (IllegalArgumentException e) { throw bad("invalid_json", e.getMessage()); }
    }

    private static String str(Map<?, ?> m, String k) {
        if (!(m.get(k) instanceof String v) || v.isBlank()) throw bad("invalid_" + k, k + " é obrigatório (texto)");
        return v;
    }

    /** Dinheiro só aceita inteiro (centavos); decimais são rejeitados de propósito. */
    private static long lng(Map<?, ?> m, String k, long def) {
        Object v = m.get(k);
        if (v == null) return def;
        if (v instanceof Long l) return l;
        throw bad("invalid_" + k, k + " deve ser um número inteiro");
    }

    private static int integer(Map<?, ?> m, String k, int def) {
        long v = lng(m, k, def);
        if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) throw bad("invalid_" + k, k + " fora do intervalo");
        return (int) v;
    }

    private static ApiException bad(String code, String msg) { return new ApiException(400, code, msg); }
    private static ApiException notFound() { return new ApiException(404, "not_found", "rota não encontrada"); }

    private static void send(HttpExchange ex, int status, Object body) throws IOException {
        byte[] out = Json.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
        ex.getResponseBody().write(out);
    }

    // ---------- main ----------

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));
        String host = System.getenv().getOrDefault("BIND_HOST", "127.0.0.1");
        String origin = System.getenv("PAY_ALLOWED_ORIGIN");
        if (origin != null) origin = origin.strip().replaceAll("/+$", "");
        if (origin != null && origin.isEmpty()) origin = null;
        String apiKey = System.getenv().getOrDefault("PAY_API_KEY", "dev-key");
        String whSecret = System.getenv().getOrDefault("PAY_WEBHOOK_SECRET", "whsec_dev");

        Ledger ledger = new Ledger();
        Acquirer.Fake acquirer = new Acquirer.Fake();
        Webhooks webhooks = new Webhooks(whSecret, Webhooks.httpSender());
        PaymentService svc = new PaymentService(ledger, acquirer, webhooks, LocalDate::now);
        boolean local = host.equals("127.0.0.1") || host.equals("localhost");
        if (!local && apiKey.length() < 16)
            throw new IllegalStateException("Defina PAY_API_KEY com pelo menos 16 caracteres antes de expor a API na internet");
        start(host, port, svc, acquirer, ledger, apiKey, origin);

        ScheduledExecutorService bg = Executors.newSingleThreadScheduledExecutor();
        bg.scheduleWithFixedDelay(() -> {
            try { webhooks.deliverPending(Instant.now()); } catch (Exception e) { e.printStackTrace(); }
        }, 5, 5, TimeUnit.SECONDS);

        System.out.println("Gateway no ar em " + host + ":" + port + (local ? "  (Authorization: Bearer " + apiKey + ")" : "  (chave via PAY_API_KEY)")
                + (origin != null ? "  CORS: " + origin : "  CORS desligado"));
    }
}
