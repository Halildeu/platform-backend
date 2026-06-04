package com.example.permission.service;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.commonauth.openfga.OpenFgaProperties;
import com.example.permission.model.GrantType;
import com.example.permission.model.PermissionType;
import com.example.permission.model.Role;
import com.example.permission.model.RolePermission;
import com.example.permission.model.UserRoleAssignment;
import com.example.permission.repository.RolePermissionRepository;
import com.example.permission.repository.UserRoleAssignmentRepository;
import dev.openfga.sdk.api.client.OpenFgaClient;
import dev.openfga.sdk.api.configuration.ClientConfiguration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AG-028 revoke-orphan fix (platform-k8s-gitops #1272) — END-TO-END proof
 * against a real OpenFGA container that revoking a grant deletes the OpenFGA
 * tuple (allowed → deny), through {@link TupleSyncService} and the real
 * {@link OpenFgaAuthzService} (incl. the new Read-API enumeration in
 * {@link OpenFgaAuthzService#readUserTuples}).
 *
 * <p>Mirrors the live testai scenario: user {@code 9003}/{@code 9004} granted
 * {@code can_manage} on {@code module:endpoint-admin} via role 21, then
 * revoked. Before the fix the tuple survived both revoke shapes.
 *
 * <p>Excluded from the default unit run via {@code @Tag("integration")} (see
 * permission-service surefire {@code excludedGroups}); run with
 * {@code ./mvnw -pl permission-service test -Pintegration-tests}. Gracefully
 * disabled when Docker is unavailable.
 */
@Testcontainers(disabledWithoutDocker = true)
@Tag("integration")
class TupleSyncRevokeDeletionIntegrationTest {

    @Container
    static final GenericContainer<?> openfga = new GenericContainer<>("openfga/openfga:v1.8.5")
            .withExposedPorts(8080, 8081)
            .withCommand("run")
            .waitingFor(Wait.forHttp("/healthz").forPort(8080).forStatusCode(200));

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static String apiUrl;
    private static String storeId;
    private static String modelId;

    private OpenFgaAuthzService authzService;
    private RolePermissionRepository rolePermissionRepository;
    private UserRoleAssignmentRepository assignmentRepository;
    private AuthzVersionService authzVersionService;
    private TupleSyncService service;

    // Simplified feature-type model (module/action/report/report_group) — every
    // relation is a direct [user] binding, which is all the revoke-orphan
    // assertion needs (write a tuple → check true → delete → check false). The
    // four types must exist so readUserTuples can Read each type filter.
    private static final String MODEL_JSON = """
        {
          "schema_version": "1.1",
          "type_definitions": [
            {"type": "user"},
            {
              "type": "module",
              "relations": {
                "can_view": {"this": {}},
                "can_edit": {"this": {}},
                "can_manage": {"this": {}},
                "blocked": {"this": {}}
              },
              "metadata": {"relations": {
                "can_view": {"directly_related_user_types": [{"type": "user"}]},
                "can_edit": {"directly_related_user_types": [{"type": "user"}]},
                "can_manage": {"directly_related_user_types": [{"type": "user"}]},
                "blocked": {"directly_related_user_types": [{"type": "user"}]}
              }}
            },
            {
              "type": "action",
              "relations": {
                "allowed": {"this": {}},
                "blocked": {"this": {}}
              },
              "metadata": {"relations": {
                "allowed": {"directly_related_user_types": [{"type": "user"}]},
                "blocked": {"directly_related_user_types": [{"type": "user"}]}
              }}
            },
            {
              "type": "report",
              "relations": {
                "can_view": {"this": {}},
                "can_edit": {"this": {}},
                "blocked": {"this": {}}
              },
              "metadata": {"relations": {
                "can_view": {"directly_related_user_types": [{"type": "user"}]},
                "can_edit": {"directly_related_user_types": [{"type": "user"}]},
                "blocked": {"directly_related_user_types": [{"type": "user"}]}
              }}
            },
            {
              "type": "report_group",
              "relations": {
                "can_view": {"this": {}},
                "can_edit": {"this": {}},
                "blocked": {"this": {}}
              },
              "metadata": {"relations": {
                "can_view": {"directly_related_user_types": [{"type": "user"}]},
                "can_edit": {"directly_related_user_types": [{"type": "user"}]},
                "blocked": {"directly_related_user_types": [{"type": "user"}]}
              }}
            }
          ]
        }
        """;

    @BeforeAll
    static void provisionStoreAndModel() throws Exception {
        apiUrl = "http://" + openfga.getHost() + ":" + openfga.getMappedPort(8080);
        var storeResp = post("/stores", "{\"name\":\"revoke-orphan-it\"}");
        storeId = json(storeResp.body(), "id");
        var modelResp = post("/stores/" + storeId + "/authorization-models", MODEL_JSON);
        modelId = json(modelResp.body(), "authorization_model_id");
    }

    @BeforeEach
    void buildService() throws Exception {
        var props = new OpenFgaProperties();
        props.setEnabled(true);
        props.setApiUrl(apiUrl);
        props.setStoreId(storeId);
        props.setModelId(modelId);

        var cfg = new ClientConfiguration()
                .apiUrl(apiUrl)
                .storeId(storeId)
                .authorizationModelId(modelId);
        OpenFgaClient client = new OpenFgaClient(cfg);
        authzService = new OpenFgaAuthzService(client, props);

        rolePermissionRepository = mock(RolePermissionRepository.class);
        assignmentRepository = mock(UserRoleAssignmentRepository.class);
        authzVersionService = mock(AuthzVersionService.class);
        service = new TupleSyncService(
                authzService, rolePermissionRepository, assignmentRepository, authzVersionService, null);
    }

    @Test
    @DisplayName("Bug A: emptying the role's granules deletes the can_manage tuple (allowed → deny)")
    void emptyGranuleDeletesTupleLive() {
        String user = "9003";
        // GRANT: role 21 assigned with MODULE endpoint-admin MANAGE
        var assignment = assignmentToRole(21L);
        var grant = perm(PermissionType.MODULE, "endpoint-admin", GrantType.MANAGE);
        when(assignmentRepository.findActiveAssignments(9003L)).thenReturn(List.of(assignment));
        when(rolePermissionRepository.findByRoleIdIn(anyList())).thenReturn(List.of(grant));
        service.refreshFeatureTuples(user);
        assertThat(authzService.check(user, "can_manage", "module", "endpoint-admin"))
                .as("grant must allow can_manage").isTrue();

        // REVOKE via PUT /granules []: member still assigned, granules emptied
        when(rolePermissionRepository.findByRoleIdIn(anyList())).thenReturn(List.of());
        service.refreshFeatureTuples(user);
        assertThat(authzService.check(user, "can_manage", "module", "endpoint-admin"))
                .as("emptied granule must delete the OpenFGA tuple").isFalse();
    }

    @Test
    @DisplayName("Bug B: removing the member's last role deletes the can_manage tuple (allowed → deny)")
    void removeLastRoleDeletesTupleLive() {
        String user = "9004";
        // GRANT
        var assignment = assignmentToRole(21L);
        var grant = perm(PermissionType.MODULE, "endpoint-admin", GrantType.MANAGE);
        when(assignmentRepository.findActiveAssignments(9004L)).thenReturn(List.of(assignment));
        when(rolePermissionRepository.findByRoleIdIn(anyList())).thenReturn(List.of(grant));
        service.refreshFeatureTuples(user);
        assertThat(authzService.check(user, "can_manage", "module", "endpoint-admin"))
                .as("grant must allow can_manage").isTrue();

        // REVOKE via DELETE /members/{id}: no active assignments remain
        when(assignmentRepository.findActiveAssignments(9004L)).thenReturn(List.of());
        service.refreshFeatureTuples(user);
        assertThat(authzService.check(user, "can_manage", "module", "endpoint-admin"))
                .as("removing the last role must delete the OpenFGA tuple").isFalse();
    }

    @Test
    @DisplayName("Mixed user: granule revoke deletes the granule tuple but PRESERVES the legacy module tuple")
    void mixedUserGranuleRevokeSparesLegacyLive() {
        String user = "9005";
        // GRANT: role grants legacy VIEW_ACCESS (module:ACCESS#can_view) AND
        // granule endpoint-admin MANAGE. Legacy write goes through the legacy
        // path (writeLegacyTuplesForUser); granule via refresh.
        var assignment = assignmentToRole(21L);
        var legacyView = legacyPerm("VIEW_ACCESS");
        var granuleManage = perm(PermissionType.MODULE, "endpoint-admin", GrantType.MANAGE);
        when(assignmentRepository.findActiveAssignments(9005L)).thenReturn(List.of(assignment));
        when(rolePermissionRepository.findByRoleIdIn(anyList()))
                .thenReturn(List.of(legacyView, granuleManage));
        service.refreshFeatureTuples(user);
        service.writeLegacyTuplesForUser(user, true);
        assertThat(authzService.check(user, "can_manage", "module", "endpoint-admin")).isTrue();
        assertThat(authzService.check(user, "can_view", "module", "ACCESS")).isTrue();

        // REVOKE the granule grant only (role now grants legacy VIEW_ACCESS).
        when(rolePermissionRepository.findByRoleIdIn(anyList())).thenReturn(List.of(legacyView));
        service.refreshFeatureTuples(user);
        assertThat(authzService.check(user, "can_manage", "module", "endpoint-admin"))
                .as("granule tuple deleted").isFalse();
        assertThat(authzService.check(user, "can_view", "module", "ACCESS"))
                .as("legacy tuple PRESERVED across granule refresh").isTrue();
    }

    @Test
    @DisplayName("Legacy bulk VIEW→MANAGE: refresh deletes can_view, legacy write adds can_manage")
    void legacyBulkViewToManageLive() {
        String user = "9006";
        var assignment = assignmentToRole(21L);
        var legacyView = legacyPerm("VIEW_ACCESS");
        when(assignmentRepository.findActiveAssignments(9006L)).thenReturn(List.of(assignment));
        when(rolePermissionRepository.findByRoleIdIn(anyList())).thenReturn(List.of(legacyView));
        service.writeLegacyTuplesForUser(user, true);
        assertThat(authzService.check(user, "can_view", "module", "ACCESS")).isTrue();

        // VIEW→MANAGE: role now grants MANAGE_ACCESS. The bulk path runs refresh
        // (deletes the stale can_view, no longer in complete-desired) then
        // writeLegacyTuplesForUser (writes the new can_manage).
        var legacyManage = legacyPerm("MANAGE_ACCESS");
        when(rolePermissionRepository.findByRoleIdIn(anyList())).thenReturn(List.of(legacyManage));
        service.refreshFeatureTuples(user, true);
        service.writeLegacyTuplesForUser(user, true);
        assertThat(authzService.check(user, "can_view", "module", "ACCESS"))
                .as("old VIEW relation deleted").isFalse();
        assertThat(authzService.check(user, "can_manage", "module", "ACCESS"))
                .as("new MANAGE relation written").isTrue();
    }

    @Test
    @DisplayName("Legacy bulk VIEW→NONE: refresh deletes can_view (no legacy write needed)")
    void legacyBulkViewToNoneLive() {
        String user = "9007";
        var assignment = assignmentToRole(21L);
        var legacyView = legacyPerm("VIEW_ACCESS");
        when(assignmentRepository.findActiveAssignments(9007L)).thenReturn(List.of(assignment));
        when(rolePermissionRepository.findByRoleIdIn(anyList())).thenReturn(List.of(legacyView));
        service.writeLegacyTuplesForUser(user, true);
        assertThat(authzService.check(user, "can_view", "module", "ACCESS")).isTrue();

        // VIEW→NONE: role no longer grants the permission. refresh deletes the
        // stale legacy tuple; writeLegacyTuplesForUser writes nothing.
        when(rolePermissionRepository.findByRoleIdIn(anyList())).thenReturn(List.of());
        service.refreshFeatureTuples(user, true);
        service.writeLegacyTuplesForUser(user, true);
        assertThat(authzService.check(user, "can_view", "module", "ACCESS"))
                .as("legacy VIEW deleted on removal").isFalse();
    }

    // --- fixtures ---

    private UserRoleAssignment assignmentToRole(long roleId) {
        Role role = mock(Role.class);
        when(role.getId()).thenReturn(roleId);
        UserRoleAssignment assignment = mock(UserRoleAssignment.class);
        when(assignment.getRole()).thenReturn(role);
        return assignment;
    }

    private RolePermission perm(PermissionType type, String key, GrantType grant) {
        Role role = mock(Role.class);
        when(role.getName()).thenReturn("ag028-role");
        RolePermission rp = mock(RolePermission.class);
        when(rp.getPermissionType()).thenReturn(type);
        when(rp.getPermissionKey()).thenReturn(key);
        when(rp.getGrantType()).thenReturn(grant);
        when(rp.getRole()).thenReturn(role);
        return rp;
    }

    /** Legacy-shape RolePermission (permission FK set, granule fields null). */
    private RolePermission legacyPerm(String code) {
        com.example.permission.model.Permission permission =
                mock(com.example.permission.model.Permission.class);
        when(permission.getCode()).thenReturn(code);
        RolePermission rp = mock(RolePermission.class);
        when(rp.getPermission()).thenReturn(permission);
        return rp;
    }

    // --- HTTP helpers (mirror OpenFgaContainerTest) ---

    private static HttpResponse<String> post(String path, String body) throws Exception {
        var req = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return HTTP.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private static String json(String body, String field) {
        var pattern = "\"" + field + "\":\"";
        int start = body.indexOf(pattern);
        if (start < 0) return null;
        start += pattern.length();
        int end = body.indexOf("\"", start);
        return body.substring(start, end);
    }
}
