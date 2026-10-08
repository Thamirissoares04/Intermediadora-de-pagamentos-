package br.pay;

import java.util.*;

/** Fronteira com o adquirente/PSP parceiro. Trocar o Fake por uma integração real não muda o resto do sistema. */
public interface Acquirer {
    record Result(boolean approved, String ref, String reason) {}
    /** Linha do arquivo de movimentação que o adquirente envia para conciliação. */
    record Movement(String ref, long amount, boolean refunded) {}

    Result authorizeAndCapture(String paymentId, long amount, Models.Method method, String token);

    void refund(String ref);

    List<Movement> report();

    /** Adquirente simulado. Cartão com token "tok_decline" é recusado; qualquer outro token é aprovado. */
    final class Fake implements Acquirer {
        private final Map<String, Movement> txs = new LinkedHashMap<>();
        private int seq = 0;

        @Override
        public synchronized Result authorizeAndCapture(String paymentId, long amount, Models.Method method, String token) {
            if (method == Models.Method.CARD) {
                if (token == null || token.isBlank()) return new Result(false, null, "invalid_token");
                if (token.equals("tok_decline")) return new Result(false, null, "insufficient_funds");
            }
            String ref = "acq_" + (++seq);
            txs.put(ref, new Movement(ref, amount, false));
            return new Result(true, ref, null);
        }

        @Override
        public synchronized void refund(String ref) {
            Movement m = txs.get(ref);
            if (m == null) throw new IllegalArgumentException("ref desconhecida: " + ref);
            txs.put(ref, new Movement(ref, m.amount(), true));
        }

        @Override
        public synchronized List<Movement> report() {
            return List.copyOf(txs.values());
        }

        // Ganchos para simular divergências nos testes de conciliação.
        public synchronized void forceAmount(String ref, long amount) {
            Movement m = txs.get(ref);
            txs.put(ref, new Movement(ref, amount, m.refunded()));
        }

        public synchronized void addGhost(String ref, long amount) {
            txs.put(ref, new Movement(ref, amount, false));
        }
    }
}
