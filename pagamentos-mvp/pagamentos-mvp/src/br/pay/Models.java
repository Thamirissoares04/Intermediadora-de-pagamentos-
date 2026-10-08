package br.pay;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Tipos de domínio. Dinheiro SEMPRE em centavos (long) — nunca double/float. */
public final class Models {
    private Models() {}

    public enum Method { PIX, CARD, BOLETO }
    public enum PaymentStatus { DECLINED, CAPTURED, REFUNDED }
    public enum ReceivableStatus { PENDING, SETTLED, CANCELLED }

    public record Merchant(String id, String name, int feeBps, long fixedFeeCents) {}

    /** Parte (em basis points, 10000 = 100%) do valor líquido destinada a outro recebedor (marketplace). */
    public record SplitRule(String merchantId, int bps) {}

    public record Payment(String id, String merchantId, long amount, Method method, int installments,
                          PaymentStatus status, String acquirerRef, String declineReason,
                          long platformFee, List<SplitRule> splits, Instant createdAt) {
        Payment with(PaymentStatus s) {
            return new Payment(id, merchantId, amount, method, installments, s, acquirerRef, declineReason,
                    platformFee, splits, createdAt);
        }
    }

    /** Item da agenda de recebíveis: quanto cada recebedor recebe e quando fica disponível. */
    public record Receivable(String id, String paymentId, String merchantId, long amount,
                             LocalDate dueDate, ReceivableStatus status) {
        Receivable with(ReceivableStatus s) {
            return new Receivable(id, paymentId, merchantId, amount, dueDate, s);
        }
    }

    public record ChargeRequest(String idempotencyKey, String merchantId, long amount, Method method,
                                int installments, String token, List<SplitRule> splits) {}
}
