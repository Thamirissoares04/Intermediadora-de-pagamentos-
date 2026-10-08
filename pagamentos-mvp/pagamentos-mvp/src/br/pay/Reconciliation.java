package br.pay;

import java.util.*;

/** Conciliação: cruza o que registramos com o arquivo do adquirente e aponta toda divergência. */
public final class Reconciliation {
    private Reconciliation() {}

    public record Result(List<String> matched, List<String> missingAtAcquirer, List<String> unknownToUs,
                         List<String> amountMismatch, List<String> statusMismatch) {
        public boolean clean() {
            return missingAtAcquirer.isEmpty() && unknownToUs.isEmpty() && amountMismatch.isEmpty() && statusMismatch.isEmpty();
        }
    }

    public static Result run(Collection<Models.Payment> ours, List<Acquirer.Movement> theirs) {
        Map<String, Acquirer.Movement> byRef = new HashMap<>();
        for (Acquirer.Movement m : theirs) byRef.put(m.ref(), m);

        List<String> matched = new ArrayList<>(), missing = new ArrayList<>(), unknown = new ArrayList<>(),
                amount = new ArrayList<>(), status = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (Models.Payment p : ours) {
            if (p.acquirerRef() == null) continue; // recusados não existem no adquirente
            Acquirer.Movement m = byRef.get(p.acquirerRef());
            if (m == null) { missing.add(p.id()); continue; }
            seen.add(m.ref());
            if (m.amount() != p.amount())
                amount.add(p.id() + " nosso=" + p.amount() + " adquirente=" + m.amount());
            else if (m.refunded() != (p.status() == Models.PaymentStatus.REFUNDED))
                status.add(p.id());
            else matched.add(p.id());
        }
        for (Acquirer.Movement m : theirs) if (!seen.contains(m.ref())) unknown.add(m.ref());
        return new Result(matched, missing, unknown, amount, status);
    }
}
