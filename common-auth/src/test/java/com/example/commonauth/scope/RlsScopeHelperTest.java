package com.example.commonauth.scope;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Tests for RlsScopeHelper — PostgreSQL session variable injection for RLS.
 * SK-7 coverage target.
 */
@ExtendWith(MockitoExtension.class)
class RlsScopeHelperTest {

    @Mock Connection connection;
    @Mock PreparedStatement ps;

    @Test
    void applyScope_null_doesNothing() throws SQLException {
        RlsScopeHelper.applyScope(connection, null);
        verifyNoInteractions(connection);
    }

    @Test
    void applyScope_superAdmin_setsBypassRls() throws SQLException {
        when(connection.prepareStatement(anyString())).thenReturn(ps);
        var ctx = ScopeContext.superAdmin("admin1");

        RlsScopeHelper.applyScope(connection, ctx);

        var captor = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(captor.capture());
        assertTrue(captor.getValue().contains("bypass_rls"));
        verify(ps).execute();
    }

    @Test
    void applyScope_normalUser_setsUserIdAndCompanyIds() throws SQLException {
        when(connection.prepareStatement(anyString())).thenReturn(ps);
        var ctx = new ScopeContext("user1", Set.of(10L, 20L), Set.of(), Set.of(), false);

        RlsScopeHelper.applyScope(connection, ctx);

        // Should prepare 2 statements: user_id + company_ids
        verify(connection, times(2)).prepareStatement(anyString());
        verify(ps, times(2)).execute();
    }

    @Test
    void applyScope_emptyCompanyIds_setsOnlyUserId() throws SQLException {
        when(connection.prepareStatement(anyString())).thenReturn(ps);
        var ctx = ScopeContext.empty("user1");

        RlsScopeHelper.applyScope(connection, ctx);

        // Only user_id set (empty company set skipped)
        verify(connection, times(1)).prepareStatement(anyString());
    }

    @Test
    void applyScope_blankUserId_skipsUserIdSet() throws SQLException {
        when(connection.prepareStatement(anyString())).thenReturn(ps);
        var ctx = new ScopeContext("", Set.of(1L), Set.of(), Set.of(), false);

        RlsScopeHelper.applyScope(connection, ctx);

        // Only company_ids set (blank userId skipped)
        verify(connection, times(1)).prepareStatement(anyString());
    }
}
