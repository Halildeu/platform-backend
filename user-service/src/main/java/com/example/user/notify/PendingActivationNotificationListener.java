package com.example.user.notify;

import com.example.user.event.PendingActivationUserProvisionedEvent;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * #734: on the AFTER_COMMIT of a new passive-user provision (the REQUIRES_NEW
 * insert tx), asynchronously submit the admin "awaiting activation" email.
 *
 * <p>AFTER_COMMIT → only fires when the insert actually committed (no email for
 * a rolled-back race-loser). {@code @Async} on a bounded executor → the
 * login/provisioning flow never waits on it. The client swallows its own
 * errors; the {@code enabled} gate keeps it inert until configured per env.
 */
@Component
public class PendingActivationNotificationListener {

    private final PendingActivationNotificationClient client;
    private final PendingActivationNotificationProperties props;

    public PendingActivationNotificationListener(PendingActivationNotificationClient client,
                                                 PendingActivationNotificationProperties props) {
        this.client = client;
        this.props = props;
    }

    @Async("pendingActivationNotificationExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUserProvisioned(PendingActivationUserProvisionedEvent event) {
        if (!props.isEnabled()) {
            return;
        }
        client.submit(event);
    }
}
