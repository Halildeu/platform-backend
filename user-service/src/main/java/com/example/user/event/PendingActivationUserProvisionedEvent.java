package com.example.user.event;

/**
 * Published once, INSIDE the REQUIRES_NEW provisioning transaction, when a NEW
 * passive (enabled=false) user row is first inserted by M365 auto-provision
 * (#734). A {@code @TransactionalEventListener(AFTER_COMMIT)} listener turns it
 * into an admin "new user awaiting activation" email via notification-orchestrator.
 *
 * <p>Emitted ONLY on the genuine first-insert path of
 * {@code UserService.lazyProvisionFromJwt} — never for an existing row or the
 * concurrent race-loser re-fetch — so exactly one email is sent per new user.
 */
public record PendingActivationUserProvisionedEvent(
        Long userId,
        String email,
        String displayName,
        String kcSubject) {
}
