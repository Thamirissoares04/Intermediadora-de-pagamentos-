package br.pay;

import java.time.Instant;
import java.util.*;

/**
 * Livro-razão de dupla entrada, somente-inclusão (append-only).
 *
 * Regra de ouro: a soma das linhas de CADA transação é zero. Portanto a soma de todos os saldos é sempre zero.
 * Convenção: saldo positivo = valor que a plataforma deve a terceiros (ou receita própria);
 * ACQUIRER:RECEIVABLE fica negativo enquanto o adquirente ainda nos deve o dinheiro.
 * Nada é editado ou apagado: correções são novos lançamentos (estornos).
 */
public final class Ledger {
    public record Line(String account, long amount) {}
    public record Entry(String txId, String description, List<Line> lines, Instant at) {}

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Map<String, Long> balances = new HashMap<>();

    /** @return true se lançou agora; false se o txId já existia com o mesmo conteúdo (idempotente). */
    public synchronized boolean post(String txId, String description, List<Line> lines) {
        if (txId == null || txId.isBlank()) throw new IllegalArgumentException("txId obrigatório");
        if (lines == null || lines.size() < 2) throw new IllegalArgumentException("mínimo de 2 linhas");
        long sum = 0;
        for (Line l : lines) {
            if (l.amount() == 0) throw new IllegalArgumentException("linha com valor zero");
            sum = Math.addExact(sum, l.amount());
        }
        if (sum != 0) throw new IllegalArgumentException("transação desbalanceada: soma=" + sum);

        Entry existing = entries.get(txId);
        if (existing != null) {
            if (!existing.lines().equals(lines)) throw new IllegalStateException("txId reutilizado com conteúdo diferente: " + txId);
            return false;
        }
        entries.put(txId, new Entry(txId, description, List.copyOf(lines), Instant.now()));
        for (Line l : lines) balances.merge(l.account(), l.amount(), Math::addExact);
        return true;
    }

    public synchronized long balance(String account) {
        return balances.getOrDefault(account, 0L);
    }

    public synchronized List<Entry> entries() {
        return List.copyOf(entries.values());
    }

    /** Invariante: deve ser sempre 0. */
    public synchronized long totalSum() {
        long s = 0;
        for (long v : balances.values()) s += v;
        return s;
    }
}
