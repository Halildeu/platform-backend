package com.example.commonauth.openfga;

import dev.openfga.sdk.api.client.OpenFgaClient;
import dev.openfga.sdk.api.client.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Tests for OpenFgaAuthzService in ENABLED mode (production path).
 * Mocks the OpenFgaClient SDK to verify all enabled-mode branches:
 * check, listObjects, writeTuple, deleteTuple, batch, expand, checkWithReason.
 */
@ExtendWith(MockitoExtension.class)
class OpenFgaAuthzServiceEnabledTest {

    @Mock OpenFgaClient client;

    private OpenFgaAuthzService service;

    @BeforeEach
    void setUp() {
        var props = new OpenFgaProperties();
        props.setEnabled(true);
        props.setStoreId("test-store");
        props.setModelId("test-model");
        service = new OpenFgaAuthzService(client, props);
    }

    // ── check() ───────────────────────────────────────────────

    @Nested
    @DisplayName("check()")
    class Check {

        @Test
        @DisplayName("allowed response returns true")
        void check_allowed() throws Exception {
            var resp = mock(ClientCheckResponse.class);
            when(resp.getAllowed()).thenReturn(true);
            when(client.check(any(ClientCheckRequest.class)))
                    .thenReturn(CompletableFuture.completedFuture(resp));

            assertTrue(service.check("user-1", "viewer", "company", "5"));
        }

        @Test
        @DisplayName("denied response returns false")
        void check_denied() throws Exception {
            var resp = mock(ClientCheckResponse.class);
            when(resp.getAllowed()).thenReturn(false);
            when(client.check(any(ClientCheckRequest.class)))
                    .thenReturn(CompletableFuture.completedFuture(resp));

            assertFalse(service.check("user-1", "admin", "company", "5"));
        }

        @Test
        @DisplayName("exception returns false (fail-closed)")
        void check_exception_failClosed() throws Exception {
            when(client.check(any(ClientCheckRequest.class)))
                    .thenReturn(CompletableFuture.failedFuture(new RuntimeException("connection refused")));

            assertFalse(service.check("user-1", "viewer", "company", "5"));
        }

        @Test
        @DisplayName("check result is cached on second call")
        void check_cached() throws Exception {
            var resp = mock(ClientCheckResponse.class);
            when(resp.getAllowed()).thenReturn(true);
            when(client.check(any(ClientCheckRequest.class)))
                    .thenReturn(CompletableFuture.completedFuture(resp));

            assertTrue(service.check("user-cache", "viewer", "company", "1"));
            assertTrue(service.check("user-cache", "viewer", "company", "1"));

            // Only 1 API call — second is from cache
            verify(client, times(1)).check(any(ClientCheckRequest.class));
        }

        @Test
        @DisplayName("null allowed returns false")
        void check_nullAllowed() throws Exception {
            var resp = mock(ClientCheckResponse.class);
            when(resp.getAllowed()).thenReturn(null);
            when(client.check(any(ClientCheckRequest.class)))
                    .thenReturn(CompletableFuture.completedFuture(resp));

            assertFalse(service.check("user-1", "viewer", "company", "5"));
        }
    }

    // ── listObjects() ─────────────────────────────────────────

    @Nested
    @DisplayName("listObjects()")
    class ListObjects {

        @Test
        @DisplayName("strips type prefix from results")
        void listObjects_stripsPrefix() throws Exception {
            var resp = mock(ClientListObjectsResponse.class);
            when(resp.getObjects()).thenReturn(List.of("company:1", "company:5", "company:10"));
            when(client.listObjects(any(ClientListObjectsRequest.class)))
                    .thenReturn(CompletableFuture.completedFuture(resp));

            List<String> ids = service.listObjects("user-1", "viewer", "company");
            assertEquals(List.of("1", "5", "10"), ids);
        }

        @Test
        @DisplayName("null response returns empty list")
        void listObjects_nullResponse() throws Exception {
            var resp = mock(ClientListObjectsResponse.class);
            when(resp.getObjects()).thenReturn(null);
            when(client.listObjects(any(ClientListObjectsRequest.class)))
                    .thenReturn(CompletableFuture.completedFuture(resp));

            assertTrue(service.listObjects("user-1", "viewer", "company").isEmpty());
        }

        @Test
        @DisplayName("exception returns empty list")
        void listObjects_exception() throws Exception {
            when(client.listObjects(any(ClientListObjectsRequest.class)))
                    .thenReturn(CompletableFuture.failedFuture(new RuntimeException("timeout")));

            assertTrue(service.listObjects("user-1", "viewer", "company").isEmpty());
        }
    }

    // ── listObjectIds() ───────────────────────────────────────

    @Nested
    @DisplayName("listObjectIds()")
    class ListObjectIds {

        @Test
        @DisplayName("parses numeric IDs to Long set")
        void listObjectIds_parsesLongs() throws Exception {
            var resp = mock(ClientListObjectsResponse.class);
            when(resp.getObjects()).thenReturn(List.of("company:1", "company:5"));
            when(client.listObjects(any(ClientListObjectsRequest.class)))
                    .thenReturn(CompletableFuture.completedFuture(resp));

            Set<Long> ids = service.listObjectIds("user-1", "viewer", "company");
            assertEquals(Set.of(1L, 5L), ids);
        }

        @Test
        @DisplayName("skips non-numeric IDs")
        void listObjectIds_skipsNonNumeric() throws Exception {
            var resp = mock(ClientListObjectsResponse.class);
            when(resp.getObjects()).thenReturn(List.of("company:1", "company:abc", "company:3"));
            when(client.listObjects(any(ClientListObjectsRequest.class)))
                    .thenReturn(CompletableFuture.completedFuture(resp));

            Set<Long> ids = service.listObjectIds("user-1", "viewer", "company");
            assertEquals(Set.of(1L, 3L), ids);
        }
    }

    // ── writeTuple() / deleteTuple() ──────────────────────────

    @Nested
    @DisplayName("write/delete tuples")
    class Tuples {

        @Test
        @DisplayName("writeTuple calls client.write with correct tuple")
        void writeTuple_success() throws Exception {
            when(client.write(any(ClientWriteRequest.class)))
                    .thenReturn(CompletableFuture.completedFuture(null));

            assertDoesNotThrow(() -> service.writeTuple("1", "admin", "company", "5"));
            verify(client).write(any(ClientWriteRequest.class));
        }

        @Test
        @DisplayName("writeTuple failure throws RuntimeException")
        void writeTuple_failure() throws Exception {
            when(client.write(any(ClientWriteRequest.class)))
                    .thenReturn(CompletableFuture.failedFuture(new RuntimeException("write failed")));

            assertThrows(RuntimeException.class,
                    () -> service.writeTuple("1", "admin", "company", "5"));
        }

        @Test
        @DisplayName("deleteTuple calls client.write with deletes")
        void deleteTuple_success() throws Exception {
            when(client.write(any(ClientWriteRequest.class)))
                    .thenReturn(CompletableFuture.completedFuture(null));

            assertDoesNotThrow(() -> service.deleteTuple("1", "admin", "company", "5"));
            verify(client).write(any(ClientWriteRequest.class));
        }

        @Test
        @DisplayName("deleteTuple failure throws RuntimeException")
        void deleteTuple_failure() throws Exception {
            when(client.write(any(ClientWriteRequest.class)))
                    .thenReturn(CompletableFuture.failedFuture(new RuntimeException("delete failed")));

            assertThrows(RuntimeException.class,
                    () -> service.deleteTuple("1", "admin", "company", "5"));
        }

        @Test
        @DisplayName("batch writeTuples calls client.write once")
        void batchWrite_success() throws Exception {
            when(client.write(any(ClientWriteRequest.class)))
                    .thenReturn(CompletableFuture.completedFuture(null));

            var tuples = List.of(
                    OpenFgaAuthzService.writeTupleKey("1", "viewer", "company", "5"),
                    OpenFgaAuthzService.writeTupleKey("1", "admin", "company", "5"));

            assertDoesNotThrow(() -> service.writeTuples(tuples));
            verify(client, times(1)).write(any(ClientWriteRequest.class));
        }

        @Test
        @DisplayName("batch writeTuples with empty list does nothing")
        void batchWrite_empty() {
            service.writeTuples(List.of());
            verifyNoInteractions(client);
        }
    }

    // ── checkWithReason() ─────────────────────────────────────

    @Nested
    @DisplayName("checkWithReason()")
    class CheckWithReason {

        @Test
        @DisplayName("granted → reason=granted")
        void granted() throws Exception {
            var resp = mock(ClientCheckResponse.class);
            when(resp.getAllowed()).thenReturn(true);
            when(client.check(any(ClientCheckRequest.class)))
                    .thenReturn(CompletableFuture.completedFuture(resp));

            var result = service.checkWithReason("user-1", "can_view", "report", "HR");
            assertTrue(result.allowed());
            assertEquals("granted", result.reason());
        }

        @Test
        @DisplayName("denied + blocked → reason=blocked")
        void denied_blocked() throws Exception {
            // First check: denied (can_view)
            var denyResp = mock(ClientCheckResponse.class);
            when(denyResp.getAllowed()).thenReturn(false);
            // Second check: blocked=true
            var blockResp = mock(ClientCheckResponse.class);
            when(blockResp.getAllowed()).thenReturn(true);

            when(client.check(any(ClientCheckRequest.class)))
                    .thenReturn(CompletableFuture.completedFuture(denyResp))
                    .thenReturn(CompletableFuture.completedFuture(blockResp));

            var result = service.checkWithReason("user-1", "can_view", "report", "FINANCE");
            assertFalse(result.allowed());
            assertEquals("blocked", result.reason());
        }

        @Test
        @DisplayName("denied + not blocked → reason=no_relation")
        void denied_noRelation() throws Exception {
            var denyResp = mock(ClientCheckResponse.class);
            when(denyResp.getAllowed()).thenReturn(false);

            when(client.check(any(ClientCheckRequest.class)))
                    .thenReturn(CompletableFuture.completedFuture(denyResp));

            var result = service.checkWithReason("user-1", "can_view", "report", "SECRET");
            assertFalse(result.allowed());
            assertEquals("no_relation", result.reason());
        }

        @Test
        @DisplayName("check failure → allowed=false, reason=no_relation (fail-closed via check cache)")
        void checkFailure_failClosed() throws Exception {
            // check() catches exception internally, returns false (fail-closed),
            // then checkWithReason uses that false to determine reason
            when(client.check(any(ClientCheckRequest.class)))
                    .thenReturn(CompletableFuture.failedFuture(new RuntimeException("oops")));

            var result = service.checkWithReason("fail-user", "can_view", "report", "HR");
            assertFalse(result.allowed());
            // check() fail-closed returns false for both can_view and blocked checks
            assertEquals("no_relation", result.reason());
        }
    }

    // ── expand() / explainAccess() ────────────────────────────

    @Nested
    @DisplayName("expand/explain")
    class ExpandExplain {

        @Test
        @DisplayName("expand returns tree")
        void expand_returnsTree() throws Exception {
            var resp = mock(ClientExpandResponse.class);
            when(resp.getTree()).thenReturn(null); // tree structure opaque
            when(client.expand(any(ClientExpandRequest.class)))
                    .thenReturn(CompletableFuture.completedFuture(resp));

            Object tree = service.expand("company", "5", "viewer");
            // null tree is valid (SDK returns null for empty relations)
            verify(client).expand(any(ClientExpandRequest.class));
        }

        @Test
        @DisplayName("expand exception returns error map")
        void expand_exception() throws Exception {
            when(client.expand(any(ClientExpandRequest.class)))
                    .thenReturn(CompletableFuture.failedFuture(new RuntimeException("expand failed")));

            Object result = service.expand("company", "5", "viewer");
            assertTrue(result instanceof Map);
            assertNotNull(((Map<?, ?>) result).get("error"));
        }
    }

    // ── static helper methods ─────────────────────────────────

    @Nested
    @DisplayName("static helpers")
    class StaticHelpers {

        @Test
        @DisplayName("writeTupleKey formats correctly")
        void writeTupleKey_format() {
            var key = OpenFgaAuthzService.writeTupleKey("1", "admin", "company", "5");
            assertEquals("user:1", key.getUser());
            assertEquals("admin", key.getRelation());
            assertEquals("company:5", key.getObject());
        }

        @Test
        @DisplayName("deleteTupleKey formats correctly")
        void deleteTupleKey_format() {
            var key = OpenFgaAuthzService.deleteTupleKey("1", "admin", "company", "5");
            assertEquals("user:1", key.getUser());
            assertEquals("admin", key.getRelation());
            assertEquals("company:5", key.getObject());
        }
    }

    @Test
    @DisplayName("isEnabled returns true")
    void isEnabled_true() {
        assertTrue(service.isEnabled());
    }
}
