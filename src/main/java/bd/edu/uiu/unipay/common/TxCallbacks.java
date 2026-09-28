package bd.edu.uiu.unipay.common;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs side-effect callbacks (WebSocket pushes, audit logging) only <b>after</b>
 * the database transaction has committed — a recipient must never be notified
 * about money that was rolled back.
 */
public final class TxCallbacks {

    private TxCallbacks() {
    }

    public static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run(); // no transaction in scope (unit tests) — run inline
        }
    }
}
