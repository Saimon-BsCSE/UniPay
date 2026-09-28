package bd.edu.uiu.unipay.audit;

import bd.edu.uiu.unipay.config.AsyncConfig;
import bd.edu.uiu.unipay.transaction.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * Asynchronous audit-trail writer — background transaction logging that keeps
 * the interactive payment path free of non-critical I/O
 * (mandatory {@code @Async} requirement). The financial ledger row itself is
 * written synchronously inside the same ACID transaction as the balance
 * update; this service appends the immutable human-readable audit trail.
 */
@Service
public class AuditService {

    private static final Logger audit = LoggerFactory.getLogger("UNIPAY_AUDIT");

    @Async(AsyncConfig.EXECUTOR)
    public void logLedgerEntry(Transaction txn, String actorUserId) {
        audit.info("[LEDGER] txn={} type={} from={} to={} amount={} actor={}",
                txn.getTransactionId(),
                txn.getTransactionType(),
                txn.getSender().getUserId(),
                txn.getReceiver().getUserId(),
                txn.getAmount().toPlainString(),
                actorUserId);
    }
}
