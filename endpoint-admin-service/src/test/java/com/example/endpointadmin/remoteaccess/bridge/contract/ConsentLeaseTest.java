package com.example.endpointadmin.remoteaccess.bridge.contract;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Faz 22.6 T-1a — {@link ConsentLease}: attended consent as a time-bounded, locally-revocable lease. */
class ConsentLeaseTest {

    @Test
    void aGrantedNotAbortedUnexpiredLeaseIsActiveWithinItsWindow() {
        ConsentLease lease = new ConsentLease(true, false, 1000);
        assertTrue(lease.isActive(500));
        assertFalse(lease.isActive(1000)); // expiry is exclusive
        assertFalse(lease.isActive(1500)); // expired
    }

    @Test
    void aLocalAbortKillsTheLeaseEvenWithinTheWindow() {
        assertFalse(new ConsentLease(true, true, 1000).isActive(500));
    }

    @Test
    void anUngrantedLeaseIsNeverActive() {
        assertFalse(new ConsentLease(false, false, 1000).isActive(500));
    }

    @Test
    void aNegativeOrAbsentLeaseIsFailClosed() {
        assertFalse(new ConsentLease(true, false, 1000).isActive(-1)); // nonsensical clock
        assertFalse(ConsentLease.isActive(null, 500));                  // absent lease never active
        assertFalse(ConsentLease.NONE.isActive(500));
        assertTrue(ConsentLease.isActive(new ConsentLease(true, false, 1000), 500)); // null-safe accessor positive
    }
}
