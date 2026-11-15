package com.oriah.finance.events;

import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.oriah.finance.ledger.AnomalyService;
import com.oriah.finance.ledger.AnomalyService.Severity;

/**
 * An event that finance refuses (it breaks a promise it makes) goes to the dead-letter topic, and the transaction that tried to apply it rolls back. This writes down THAT it
 * was refused, in a transaction of its own that survives the rollback, so the refusal is on the books as an open error ({@code event-refused}) that blocks reporting a month
 * until the event is applied (after the cause is fixed and a re-verify) or someone has looked. It records WHAT happened (the event, its type, its producer, the kind of failure),
 * never the message: a message can carry the very values that made the event wrong.
 */
@Component
public class RefusalRecorder {

    private final AnomalyService anomalies;

    public RefusalRecorder(AnomalyService anomalies) {
        this.anomalies = anomalies;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void refused(String eventId, String type, String producer, Throwable why) {
        anomalies.raise("event-refused", eventId, Severity.ERROR, Map.of("type", type, "producer", producer, "failure", why.getClass().getSimpleName()), null);
    }

    /** The event applied after all (the cause was fixed): its standing error is closed by the system. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void applied(String eventId) {
        anomalies.autoResolve("event-refused", eventId);
    }
}
