package br.pay;

import br.pay.Models.*;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Supplier;

/**
 * Núcleo do gateway: cobrança com idempotência, split, agenda de recebíveis, liquidação e estorno.
 * Tudo em memória e protegido por um único lock (simples e correto para um MVP).
 * Em produção: PostgreSQL com transações, constraint UNIQUE na chave de idempotência e SELECT ... FOR UPDATE.
 */
public final class PaymentService {
    static final String ACQ = "ACQUIRER:RECEIVABLE";
    static final String REVENUE = "PLATFORM:REVENUE";
    static String pending(String m) { return "MERCHANT:" + m + ":PENDING"; }
    static String available(String m) { return "MERCHANT:" + m + ":AVAILABLE"; }

    private static final long MAX_AMOUNT = 100_000_000_00L; // R$ 100 milhões em centavos (evita overflow)

    private final Ledger ledger;
    private final Acquirer acquirer;
    private final Webhooks webhooks;
    private final Supplier<LocalDate> today;

    private final Map<String, Merchant> merchants = new LinkedHashMap<>();
    private final Map<String, Payment> payments = new LinkedHashMap<>();
    private final Map<String, Receivable> receivables = new LinkedHashMap<>();
    private final Map<String, String[]> idempotency = new HashMap<>(); // chave -> {fingerprint, paymentId}
    private int seq = 0;

    public PaymentService(Ledger ledger, Acquirer acquirer, Webhooks webhooks, Supplier<LocalDate> today) {
        this.ledger = ledger;
        this.acquirer = acquirer;
        this.webhooks = webhooks;
        this.today = today;
    }

    // ---------- lojistas ----------

    public synchronized Merchant registerMerchant(String id, String name, int feeBps, long fixedFeeCents, String webhookUrl) {
        if (id == null || !id.matches("[a-zA-Z0-9_-]{1,40}"))
            throw new ApiException(400, "invalid_merchant_id", "id deve ter 1-40 caracteres [a-zA-Z0-9_-]");
        if (name == null || name.isBlank()) throw new ApiException(400, "invalid_name", "name é obrigatório");
        if (feeBps < 0 || feeBps > 10_000 || fixedFeeCents < 0)
            throw new ApiException(400, "invalid_fee", "feeBps deve estar em 0..10000 e fixedFeeCents >= 0");
        if (merchants.containsKey(id)) throw new ApiException(409, "merchant_exists", "lojista já existe");
        if (webhookUrl != null && !webhookUrl.isBlank()) checkWebhookUrl(webhookUrl);
        Merchant m = new Merchant(id, name, feeBps, fixedFeeCents);
        merchants.put(id, m);
        if (webhookUrl != null && !webhookUrl.isBlank()) webhooks.register(id, webhookUrl);
        return m;
    }

    private static void checkWebhookUrl(String url) {
        try {
            URI u = URI.create(url);
            String host = u.getHost();
            boolean local = "localhost".equals(host) || "127.0.0.1".equals(host);
            boolean ok = host != null && ("https".equals(u.getScheme()) || (local && "http".equals(u.getScheme())));
            if (!ok) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) {
            throw new ApiException(400, "invalid_webhook_url", "use uma URL https válida");
        }
    }

    public synchronized Merchant merchant(String id) {
        Merchant m = merchants.get(id);
        if (m == null) throw new ApiException(404, "merchant_not_found", "lojista não encontrado");
        return m;
    }

    public synchronized Map<String, Long> balance(String merchantId) {
        merchant(merchantId);
        Map<String, Long> m = new LinkedHashMap<>();
        m.put("pending", ledger.balance(pending(merchantId)));
        m.put("available", ledger.balance(available(merchantId)));
        return m;
    }

    // ---------- cobrança ----------

    public synchronized Payment charge(ChargeRequest r) {
        if (r.idempotencyKey() == null || r.idempotencyKey().isBlank() || r.idempotencyKey().length() > 120)
            throw new ApiException(400, "idempotency_key_required", "header Idempotency-Key é obrigatório (até 120 caracteres)");
        Merchant primary = merchant(r.merchantId());
        if (r.method() == null) throw new ApiException(400, "invalid_method", "method é obrigatório");
        List<SplitRule> splits = r.splits() == null ? List.of() : r.splits();

        // Idempotência: mesma chave + mesmo corpo = mesma resposta; mesma chave + corpo diferente = conflito.
        String idemKey = r.merchantId() + ":" + r.idempotencyKey();
        String fingerprint = r.amount() + "|" + r.method() + "|" + r.installments() + "|" + r.token() + "|" + splits;
        String[] prev = idempotency.get(idemKey);
        if (prev != null) {
            if (!prev[0].equals(fingerprint))
                throw new ApiException(409, "idempotency_conflict", "Idempotency-Key já usada com parâmetros diferentes");
            return payments.get(prev[1]);
        }

        if (r.amount() <= 0 || r.amount() > MAX_AMOUNT)
            throw new ApiException(400, "invalid_amount", "amount deve estar entre 1 e " + MAX_AMOUNT + " centavos");
        int n = r.method() == Method.CARD ? Math.max(1, r.installments()) : 1;
        if (n > 12) throw new ApiException(400, "invalid_installments", "máximo de 12 parcelas");
        validateSplits(primary, splits);

        String id = "pay_" + (++seq);
        // Em produção esta chamada de rede NÃO ficaria dentro do lock/transação (usaria estado intermediário).
        Acquirer.Result res = acquirer.authorizeAndCapture(id, r.amount(), r.method(), r.token());

        if (!res.approved()) {
            Payment p = new Payment(id, primary.id(), r.amount(), r.method(), n, PaymentStatus.DECLINED,
                    null, res.reason(), 0, splits, Instant.now());
            payments.put(id, p);
            idempotency.put(idemKey, new String[]{fingerprint, id});
            webhooks.enqueue(primary.id(), "payment.declined", Map.of("payment_id", id, "reason", String.valueOf(res.reason())));
            return p;
        }

        long fee = Math.min(r.amount(), r.amount() * primary.feeBps() / 10_000 + primary.fixedFeeCents());
        long net = r.amount() - fee;

        // Split por divisão inteira; o lojista principal fica com o resto, então a soma fecha ao centavo.
        Map<String, Long> shares = new LinkedHashMap<>();
        shares.put(primary.id(), 0L);
        long assigned = 0;
        for (SplitRule s : splits) {
            long part = net * s.bps() / 10_000;
            shares.put(s.merchantId(), part);
            assigned += part;
        }
        shares.put(primary.id(), net - assigned);

        List<Ledger.Line> lines = new ArrayList<>();
        lines.add(new Ledger.Line(ACQ, -r.amount()));
        shares.forEach((m, v) -> { if (v > 0) lines.add(new Ledger.Line(pending(m), v)); });
        if (fee > 0) lines.add(new Ledger.Line(REVENUE, fee));
        ledger.post("charge:" + id, "Cobrança " + id, lines);

        LocalDate base = today.get();
        Method method = r.method();
        shares.forEach((m, total) -> {
            if (total <= 0) return;
            for (int i = 1; i <= n; i++) {
                long part = total / n + (i == n ? total % n : 0);
                if (part == 0) continue;
                LocalDate due = switch (method) {
                    case PIX -> base;
                    case BOLETO -> base.plusDays(1);
                    case CARD -> base.plusDays(30L * i);
                };
                String rid = id + ":" + m + ":" + i;
                receivables.put(rid, new Receivable(rid, id, m, part, due, ReceivableStatus.PENDING));
            }
        });

        Payment p = new Payment(id, primary.id(), r.amount(), r.method(), n, PaymentStatus.CAPTURED,
                res.ref(), null, fee, splits, Instant.now());
        payments.put(id, p);
        idempotency.put(idemKey, new String[]{fingerprint, id});
        webhooks.enqueue(primary.id(), "payment.captured", Map.of("payment_id", id, "amount", r.amount()));
        return p;
    }

    private void validateSplits(Merchant primary, List<SplitRule> splits) {
        long total = 0;
        Set<String> seen = new HashSet<>();
        for (SplitRule s : splits) {
            if (s == null || s.bps() <= 0 || s.bps() > 10_000)
                throw new ApiException(400, "invalid_split", "bps de cada split deve estar em 1..10000");
            if (s.merchantId() == null || s.merchantId().equals(primary.id()) || !seen.add(s.merchantId()))
                throw new ApiException(400, "invalid_split", "split duplicado ou igual ao lojista principal");
            merchant(s.merchantId());
            total += s.bps();
        }
        if (total > 10_000) throw new ApiException(400, "invalid_split", "soma dos splits excede 100%");
    }

    // ---------- liquidação ----------

    /** Move para "disponível" tudo que venceu até asOf. Idempotente (txId = settle:<recebível>). */
    public synchronized Map<String, Long> settleDue(LocalDate asOf) {
        Map<String, Long> byMerchant = new LinkedHashMap<>();
        for (Receivable x : List.copyOf(receivables.values())) {
            if (x.status() != ReceivableStatus.PENDING || x.dueDate().isAfter(asOf)) continue;
            ledger.post("settle:" + x.id(), "Liquidação " + x.id(), List.of(
                    new Ledger.Line(pending(x.merchantId()), -x.amount()),
                    new Ledger.Line(available(x.merchantId()), x.amount())));
            receivables.put(x.id(), x.with(ReceivableStatus.SETTLED));
            byMerchant.merge(x.merchantId(), x.amount(), Long::sum);
        }
        byMerchant.forEach((m, v) -> webhooks.enqueue(m, "settlement.completed", Map.of("amount", v)));
        return byMerchant;
    }

    // ---------- estorno ----------

    /** Estorno total. Antes da liquidação cancela o pendente; depois, debita o disponível (se houver saldo). */
    public synchronized Payment refund(String paymentId) {
        Payment p = payment(paymentId);
        if (p.status() == PaymentStatus.REFUNDED) return p; // idempotente
        if (p.status() != PaymentStatus.CAPTURED)
            throw new ApiException(409, "not_refundable", "só pagamentos capturados podem ser estornados");

        Map<String, Long> pend = new LinkedHashMap<>(), settled = new LinkedHashMap<>();
        List<Receivable> mine = receivables.values().stream().filter(x -> x.paymentId().equals(paymentId)).toList();
        for (Receivable x : mine) {
            if (x.status() == ReceivableStatus.PENDING) pend.merge(x.merchantId(), x.amount(), Long::sum);
            else if (x.status() == ReceivableStatus.SETTLED) settled.merge(x.merchantId(), x.amount(), Long::sum);
        }
        for (var e : settled.entrySet())
            if (ledger.balance(available(e.getKey())) < e.getValue())
                throw new ApiException(409, "insufficient_balance", "saldo disponível insuficiente para estornar: " + e.getKey());

        acquirer.refund(p.acquirerRef());
        List<Ledger.Line> lines = new ArrayList<>();
        lines.add(new Ledger.Line(ACQ, p.amount()));
        pend.forEach((m, v) -> lines.add(new Ledger.Line(pending(m), -v)));
        settled.forEach((m, v) -> lines.add(new Ledger.Line(available(m), -v)));
        if (p.platformFee() > 0) lines.add(new Ledger.Line(REVENUE, -p.platformFee()));
        ledger.post("refund:" + paymentId, "Estorno " + paymentId, lines);

        for (Receivable x : mine) receivables.put(x.id(), x.with(ReceivableStatus.CANCELLED));
        Payment refunded = p.with(PaymentStatus.REFUNDED);
        payments.put(paymentId, refunded);
        webhooks.enqueue(p.merchantId(), "payment.refunded", Map.of("payment_id", paymentId, "amount", p.amount()));
        return refunded;
    }

    // ---------- consultas ----------

    public synchronized Payment payment(String id) {
        Payment p = payments.get(id);
        if (p == null) throw new ApiException(404, "payment_not_found", "pagamento não encontrado");
        return p;
    }

    public synchronized List<Payment> payments() { return List.copyOf(payments.values()); }

    public synchronized List<Receivable> receivablesOf(String paymentId) {
        return receivables.values().stream().filter(x -> x.paymentId().equals(paymentId)).toList();
    }
}
