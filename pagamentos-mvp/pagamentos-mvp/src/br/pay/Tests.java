package br.pay;

import br.pay.Models.*;
import com.sun.net.httpserver.HttpServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;

/** Testes sem framework (só JDK): rode com  java -cp out br.pay.Tests  — sai com código 1 se algo falhar. */
public final class Tests {
    static int passed = 0, failed = 0;

    interface T { void run() throws Exception; }

    static void test(String name, T t) {
        try { t.run(); passed++; System.out.println("  ok      " + name); }
        catch (Throwable e) { failed++; System.out.println("  FALHOU  " + name + " -> " + e); }
    }

    static void check(boolean c, String msg) { if (!c) throw new AssertionError(msg); }

    static void eq(Object exp, Object act, String msg) {
        if (!Objects.equals(exp, act)) throw new AssertionError(msg + " (esperado=" + exp + ", obtido=" + act + ")");
    }

    static void expectApi(int status, T t) throws Exception {
        try { t.run(); }
        catch (ApiException e) { eq(status, e.status, "status HTTP do erro"); return; }
        throw new AssertionError("esperava ApiException " + status);
    }

    static final class Env {
        final Ledger ledger = new Ledger();
        final Acquirer.Fake acq = new Acquirer.Fake();
        final List<String> sent = new ArrayList<>();
        final Webhooks hooks = new Webhooks("whsec_test", (url, body, h) -> { sent.add(body); return 200; });
        final LocalDate[] day = {LocalDate.of(2026, 10, 7)};
        final PaymentService svc = new PaymentService(ledger, acq, hooks, () -> day[0]);
    }

    static ChargeRequest req(String key, String merchant, long amount, Method m, int inst, String token, SplitRule... splits) {
        return new ChargeRequest(key, merchant, amount, m, inst, token, List.of(splits));
    }

    static void sumsToZero(Env e) {
        eq(0L, e.ledger.totalSum(), "invariante do ledger (soma de todos os saldos = 0)");
    }

    public static void main(String[] args) throws Exception {
        System.out.println("Executando testes...");

        test("ledger: rejeita transação desbalanceada e é idempotente", () -> {
            Ledger l = new Ledger();
            try { l.post("t1", "x", List.of(new Ledger.Line("A", 100), new Ledger.Line("B", -90))); throw new AssertionError("deveria rejeitar"); }
            catch (IllegalArgumentException ok) { /* esperado */ }
            List<Ledger.Line> ok = List.of(new Ledger.Line("A", 100), new Ledger.Line("B", -100));
            check(l.post("t2", "x", ok), "primeiro lançamento");
            check(!l.post("t2", "x", ok), "repetição não duplica");
            eq(100L, l.balance("A"), "saldo A");
            try { l.post("t2", "x", List.of(new Ledger.Line("A", 5), new Ledger.Line("B", -5))); throw new AssertionError("deveria rejeitar"); }
            catch (IllegalStateException ok2) { /* txId reutilizado com conteúdo diferente */ }
        });

        test("cobrança Pix com split: soma fecha ao centavo e ledger balanceia", () -> {
            Env e = new Env();
            e.svc.registerMerchant("loja", "Loja", 299, 30, null);
            e.svc.registerMerchant("sellerA", "Seller A", 0, 0, null);
            Payment p = e.svc.charge(req("k1", "loja", 10_001, Method.PIX, 1, null, new SplitRule("sellerA", 3333)));
            eq(PaymentStatus.CAPTURED, p.status(), "status");
            eq(329L, p.platformFee(), "taxa = 2,99% + R$0,30 (arredondada p/ baixo)");
            long a = e.ledger.balance("MERCHANT:sellerA:PENDING"), l = e.ledger.balance("MERCHANT:loja:PENDING");
            eq(3223L, a, "parte do sellerA");
            eq(10_001L, a + l + p.platformFee(), "partes + taxa = valor cobrado");
            eq(-10_001L, e.ledger.balance("ACQUIRER:RECEIVABLE"), "a receber do adquirente");
            sumsToZero(e);
        });

        test("idempotência: mesma chave repete resposta; corpo diferente dá 409", () -> {
            Env e = new Env();
            e.svc.registerMerchant("loja", "Loja", 0, 0, null);
            Payment a = e.svc.charge(req("dup", "loja", 5_000, Method.PIX, 1, null));
            Payment b = e.svc.charge(req("dup", "loja", 5_000, Method.PIX, 1, null));
            eq(a.id(), b.id(), "mesmo pagamento");
            eq(1, e.ledger.entries().size(), "um único lançamento");
            expectApi(409, () -> e.svc.charge(req("dup", "loja", 5_001, Method.PIX, 1, null)));
            expectApi(400, () -> e.svc.charge(req(null, "loja", 5_000, Method.PIX, 1, null)));
        });

        test("recusa do adquirente não mexe no ledger", () -> {
            Env e = new Env();
            e.svc.registerMerchant("loja", "Loja", 0, 0, null);
            Payment p = e.svc.charge(req("k", "loja", 9_000, Method.CARD, 1, "tok_decline"));
            eq(PaymentStatus.DECLINED, p.status(), "status");
            eq("insufficient_funds", p.declineReason(), "motivo");
            eq(0, e.ledger.entries().size(), "ledger vazio");
        });

        test("validações: valor, parcelas, split inválido, lojista inexistente", () -> {
            Env e = new Env();
            e.svc.registerMerchant("loja", "Loja", 0, 0, null);
            e.svc.registerMerchant("s1", "S1", 0, 0, null);
            expectApi(400, () -> e.svc.charge(req("a", "loja", 0, Method.PIX, 1, null)));
            expectApi(400, () -> e.svc.charge(req("b", "loja", -5, Method.PIX, 1, null)));
            expectApi(400, () -> e.svc.charge(req("c", "loja", 1000, Method.CARD, 13, "tok")));
            expectApi(400, () -> e.svc.charge(req("d", "loja", 1000, Method.PIX, 1, null, new SplitRule("s1", 6000), new SplitRule("loja", 10)))); 
            expectApi(400, () -> e.svc.charge(req("e", "loja", 1000, Method.PIX, 1, null, new SplitRule("s1", 10_001))));
            expectApi(404, () -> e.svc.charge(req("f", "nao-existe", 1000, Method.PIX, 1, null)));
            expectApi(404, () -> e.svc.charge(req("g", "loja", 1000, Method.PIX, 1, null, new SplitRule("fantasma", 100))));
            expectApi(400, () -> e.svc.registerMerchant("id:ruim", "X", 0, 0, null));
            expectApi(400, () -> e.svc.registerMerchant("ok", "X", 0, 0, "http://169.254.169.254/x"));
        });

        test("cartão 3x: agenda D+30/60/90 e liquidação parcela a parcela", () -> {
            Env e = new Env();
            e.svc.registerMerchant("m", "M", 0, 0, null);
            Payment p = e.svc.charge(req("k", "m", 10_000, Method.CARD, 3, "tok_ok"));
            List<Receivable> rs = e.svc.receivablesOf(p.id());
            eq(3, rs.size(), "3 parcelas");
            eq(List.of(3333L, 3333L, 3334L), rs.stream().map(Receivable::amount).toList(), "valores (resto na última)");
            eq(LocalDate.of(2026, 11, 6), rs.get(0).dueDate(), "D+30");
            eq(LocalDate.of(2027, 1, 5), rs.get(2).dueDate(), "D+90");
            e.day[0] = LocalDate.of(2026, 11, 6);
            e.svc.settleDue(e.day[0]);
            eq(3333L, e.svc.balance("m").get("available"), "disponível após 1ª parcela");
            eq(6667L, e.svc.balance("m").get("pending"), "pendente");
            e.svc.settleDue(e.day[0]); // rodar de novo não duplica
            eq(3333L, e.svc.balance("m").get("available"), "liquidação idempotente");
            sumsToZero(e);
        });

        test("Pix liquida no mesmo dia; boleto em D+1", () -> {
            Env e = new Env();
            e.svc.registerMerchant("m", "M", 0, 0, null);
            e.svc.charge(req("p", "m", 1_000, Method.PIX, 1, null));
            e.svc.charge(req("b", "m", 2_000, Method.BOLETO, 1, null));
            e.svc.settleDue(e.day[0]);
            eq(1_000L, e.svc.balance("m").get("available"), "só o Pix hoje");
            e.svc.settleDue(e.day[0].plusDays(1));
            eq(3_000L, e.svc.balance("m").get("available"), "boleto no dia seguinte");
        });

        test("estorno antes da liquidação zera pendente e receita; repetir é idempotente", () -> {
            Env e = new Env();
            e.svc.registerMerchant("m", "M", 299, 30, null);
            Payment p = e.svc.charge(req("k", "m", 10_000, Method.PIX, 1, null));
            Payment r = e.svc.refund(p.id());
            eq(PaymentStatus.REFUNDED, r.status(), "status");
            eq(0L, e.svc.balance("m").get("pending"), "pendente zerado");
            eq(0L, e.ledger.balance("PLATFORM:REVENUE"), "receita devolvida");
            eq(0L, e.ledger.balance("ACQUIRER:RECEIVABLE"), "adquirente zerado");
            eq(PaymentStatus.REFUNDED, e.svc.refund(p.id()).status(), "segunda chamada");
            sumsToZero(e);
        });

        test("estorno depois da liquidação debita o disponível", () -> {
            Env e = new Env();
            e.svc.registerMerchant("m", "M", 0, 0, null);
            Payment p = e.svc.charge(req("k", "m", 4_000, Method.PIX, 1, null));
            e.svc.settleDue(e.day[0]);
            eq(4_000L, e.svc.balance("m").get("available"), "disponível");
            e.svc.refund(p.id());
            eq(0L, e.svc.balance("m").get("available"), "disponível após estorno");
            sumsToZero(e);
        });

        test("estorno de pagamento recusado ou inexistente é rejeitado", () -> {
            Env e = new Env();
            e.svc.registerMerchant("m", "M", 0, 0, null);
            Payment d = e.svc.charge(req("k", "m", 100, Method.CARD, 1, "tok_decline"));
            expectApi(409, () -> e.svc.refund(d.id()));
            expectApi(404, () -> e.svc.refund("pay_999"));
        });

        test("conciliação: detecta divergência de valor, fantasma e ausência", () -> {
            Env e = new Env();
            e.svc.registerMerchant("m", "M", 0, 0, null);
            Payment a = e.svc.charge(req("a", "m", 1_000, Method.PIX, 1, null));
            Payment b = e.svc.charge(req("b", "m", 2_000, Method.PIX, 1, null));
            e.svc.charge(req("c", "m", 3_000, Method.PIX, 1, null));
            eq(true, Reconciliation.run(e.svc.payments(), e.acq.report()).clean(), "tudo bate");
            e.acq.forceAmount(a.acquirerRef(), 999);
            e.acq.addGhost("acq_fantasma", 777);
            Reconciliation.Result r = Reconciliation.run(e.svc.payments(), e.acq.report());
            eq(1, r.amountMismatch().size(), "valor divergente");
            eq(List.of("acq_fantasma"), r.unknownToUs(), "só no adquirente");
            Reconciliation.Result r2 = Reconciliation.run(e.svc.payments(), e.acq.report().subList(1, 3));
            eq(true, r2.missingAtAcquirer().contains(a.id()), "ausente no adquirente");
            e.svc.refund(b.id());
            eq(true, Reconciliation.run(e.svc.payments(), e.acq.report()).statusMismatch().isEmpty(), "estorno bate nos dois lados");
        });

        test("webhook: assinatura HMAC, backoff exponencial e entrega após falhas", () -> {
            List<Integer> responses = new ArrayList<>(List.of(500, 500, 200));
            List<Map<String, String>> hdrs = new ArrayList<>();
            List<String> bodies = new ArrayList<>();
            Webhooks w = new Webhooks("s3cr3t", (url, body, h) -> { hdrs.add(h); bodies.add(body); return responses.remove(0); });
            w.register("m1", "https://example.com/hook");
            w.enqueue("m1", "payment.captured", Map.of("payment_id", "pay_1"));
            Instant t0 = Instant.parse("2026-10-07T12:00:00Z");
            eq(0, w.deliverPending(t0), "1ª tentativa falha");
            w.deliverPending(t0.plusSeconds(10));
            eq(1, bodies.size(), "não reenvia antes do backoff (30s)");
            eq(0, w.deliverPending(t0.plusSeconds(30)), "2ª tentativa falha");
            w.deliverPending(t0.plusSeconds(89));
            eq(2, bodies.size(), "não reenvia antes do backoff (60s)");
            eq(1, w.deliverPending(t0.plusSeconds(90)), "3ª tentativa entrega");
            eq("DELIVERED", w.outbox().get(0).state, "estado");
            Map<String, String> h = hdrs.get(2);
            long now = t0.plusSeconds(90).getEpochSecond();
            check(Webhooks.verify("s3cr3t", h.get("X-Webhook-Timestamp"), bodies.get(2), h.get("X-Webhook-Signature"), now, 300), "assinatura válida");
            check(!Webhooks.verify("outro", h.get("X-Webhook-Timestamp"), bodies.get(2), h.get("X-Webhook-Signature"), now, 300), "segredo errado");
            check(!Webhooks.verify("s3cr3t", h.get("X-Webhook-Timestamp"), bodies.get(2) + " ", h.get("X-Webhook-Signature"), now, 300), "corpo adulterado");
            check(!Webhooks.verify("s3cr3t", h.get("X-Webhook-Timestamp"), bodies.get(2), h.get("X-Webhook-Signature"), now + 3600, 300), "replay antigo");
        });

        test("webhook: vira DEAD após o máximo de tentativas", () -> {
            int[] calls = {0};
            Webhooks w = new Webhooks("s", (u, b, h) -> { calls[0]++; throw new java.io.IOException("fora do ar"); });
            w.register("m", "https://example.com/h");
            w.enqueue("m", "x", Map.of());
            Instant t0 = Instant.parse("2026-10-07T00:00:00Z");
            for (int i = 0; i < 20; i++) w.deliverPending(t0.plusSeconds(86_400L * i));
            eq("DEAD", w.outbox().get(0).state, "estado");
            eq(Webhooks.MAX_ATTEMPTS, calls[0], "nº de tentativas");
        });

        test("JSON: parse/serialize, escapes, inteiros vs decimais, limite de profundidade", () -> {
            Map<String, Object> o = Json.parseObject("{\"a\":1,\"b\":1.5,\"c\":[true,null,\"x\\n\\u00e9\"],\"d\":{\"e\":\"\\\"q\\\"\"}}");
            eq(1L, o.get("a"), "inteiro vira Long");
            eq(1.5, o.get("b"), "decimal vira Double");
            eq("x\né", ((List<?>) o.get("c")).get(2), "escapes");
            eq(Json.write(o), Json.write(Json.parse(Json.write(o))), "roundtrip estável");
            try { Json.parse("[".repeat(100)); throw new AssertionError("deveria limitar profundidade"); } catch (IllegalArgumentException ok) { }
            try { Json.parse("{\"a\":1} lixo"); throw new AssertionError("deveria rejeitar lixo"); } catch (IllegalArgumentException ok) { }
        });

        test("API HTTP ponta a ponta: auth, idempotência, split, liquidação, estorno, integridade", () -> {
            Env e = new Env();
            HttpServer srv = Api.start(0, e.svc, e.acq, e.ledger, "k3y");
            try {
                String base = "http://127.0.0.1:" + srv.getAddress().getPort();
                HttpClient c = HttpClient.newHttpClient();
                eq(401, call(c, "GET", base + "/v1/ledger/integrity", null, null, null).statusCode(), "sem chave");
                eq(401, call(c, "GET", base + "/v1/ledger/integrity", "errada", null, null).statusCode(), "chave errada");
                eq(200, call(c, "GET", base + "/health", null, null, null).statusCode(), "health é público");

                eq(201, call(c, "POST", base + "/v1/merchants", "k3y", null,
                        "{\"id\":\"loja\",\"name\":\"Loja\",\"feeBps\":300,\"fixedFeeCents\":0}").statusCode(), "cria loja");
                eq(201, call(c, "POST", base + "/v1/merchants", "k3y", null,
                        "{\"id\":\"vend\",\"name\":\"Vendedor\"}").statusCode(), "cria vendedor");
                eq(409, call(c, "POST", base + "/v1/merchants", "k3y", null, "{\"id\":\"loja\",\"name\":\"Dup\"}").statusCode(), "duplicada");

                String charge = "{\"merchantId\":\"loja\",\"amount\":10000,\"method\":\"pix\",\"splits\":[{\"merchantId\":\"vend\",\"bps\":5000}]}";
                HttpResponse<String> r1 = call(c, "POST", base + "/v1/payments", "k3y", "abc-1", charge);
                eq(201, r1.statusCode(), "cobrança");
                HttpResponse<String> r2 = call(c, "POST", base + "/v1/payments", "k3y", "abc-1", charge);
                eq(Json.parseObject(r1.body()).get("id"), Json.parseObject(r2.body()).get("id"), "replay devolve o mesmo pagamento");
                eq(400, call(c, "POST", base + "/v1/payments", "k3y", null, charge).statusCode(), "sem Idempotency-Key");
                eq(400, call(c, "POST", base + "/v1/payments", "k3y", "x", charge.replace("10000", "100.5")).statusCode(), "decimal em centavos");
                eq(400, call(c, "POST", base + "/v1/payments", "k3y", "y", "{nao é json").statusCode(), "JSON inválido");
                eq(402, call(c, "POST", base + "/v1/payments", "k3y", "z",
                        "{\"merchantId\":\"loja\",\"amount\":100,\"method\":\"CARD\",\"token\":\"tok_decline\"}").statusCode(), "recusado = 402");

                String id = (String) Json.parseObject(r1.body()).get("id");
                eq(200, call(c, "POST", base + "/v1/settlements/run", "k3y", null, "{\"date\":\"2026-10-07\"}").statusCode(), "liquidação");
                Map<String, Object> bal = Json.parseObject(call(c, "GET", base + "/v1/merchants/vend/balance", "k3y", null, null).body());
                eq(4850L, bal.get("available"), "vendedor recebe 50% do líquido (10000 - 3%)");
                eq(200, call(c, "POST", base + "/v1/payments/" + id + "/refund", "k3y", null, null).statusCode(), "estorno");
                Map<String, Object> bal2 = Json.parseObject(call(c, "GET", base + "/v1/merchants/vend/balance", "k3y", null, null).body());
                eq(0L, bal2.get("available"), "saldo após estorno");
                Map<String, Object> integ = Json.parseObject(call(c, "GET", base + "/v1/ledger/integrity", "k3y", null, null).body());
                eq(true, integ.get("ok"), "ledger íntegro");
                eq(404, call(c, "GET", base + "/v1/nada", "k3y", null, null).statusCode(), "rota inexistente");
            } finally {
                srv.stop(0);
            }
        });

        test("CORS: só a origem configurada passa; preflight não exige chave", () -> {
            Env e = new Env();
            HttpServer srv = Api.start("127.0.0.1", 0, e.svc, e.acq, e.ledger, "k3y", "https://meu-app.exemplo.com");
            try {
                String url = "http://127.0.0.1:" + srv.getAddress().getPort() + "/v1/merchants";
                HttpClient c = HttpClient.newHttpClient();
                HttpResponse<String> ok = c.send(HttpRequest.newBuilder(URI.create(url)).method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                        .header("Origin", "https://meu-app.exemplo.com").build(), HttpResponse.BodyHandlers.ofString());
                eq(204, ok.statusCode(), "preflight liberado");
                eq("https://meu-app.exemplo.com", ok.headers().firstValue("Access-Control-Allow-Origin").orElse(null), "origem permitida");
                check(ok.headers().firstValue("Access-Control-Allow-Headers").orElse("").contains("Idempotency-Key"), "libera Idempotency-Key");
                HttpResponse<String> bad = c.send(HttpRequest.newBuilder(URI.create(url)).method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                        .header("Origin", "https://site-malicioso.com").build(), HttpResponse.BodyHandlers.ofString());
                eq(403, bad.statusCode(), "origem desconhecida no preflight");
                HttpResponse<String> get = c.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + srv.getAddress().getPort() + "/health"))
                        .header("Origin", "https://site-malicioso.com").GET().build(), HttpResponse.BodyHandlers.ofString());
                check(get.headers().firstValue("Access-Control-Allow-Origin").isEmpty(), "sem cabeçalho CORS para origem desconhecida");
            } finally {
                srv.stop(0);
            }
        });

        System.out.println("\n" + passed + " passaram, " + failed + " falharam.");
        System.exit(failed == 0 ? 0 : 1);
    }

    static HttpResponse<String> call(HttpClient c, String method, String url, String key, String idem, String body) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (key != null) b.header("Authorization", "Bearer " + key);
        if (idem != null) b.header("Idempotency-Key", idem);
        if (body != null) b.header("Content-Type", "application/json");
        return c.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }
}
