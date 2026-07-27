package com.example.report.workcube;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

class ProjectOptionsControllerTest {

    private final ProjectOptionsService service = mock(ProjectOptionsService.class);
    private final ProjectOptionsController controller = new ProjectOptionsController(service);

    @Test
    void rejectsMissingAndNonNumericCompanyHeaders() {
        assertThatThrownBy(() -> controller.list(" "))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));

        assertThatThrownBy(() -> controller.list("company-35"))
                .isInstanceOfSatisfying(
                        ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void returnsExplicitReadOnlySourceOutageWithoutLeakingDatabaseDetails() {
        when(service.findAuthorized(any(), eq(35L)))
                .thenThrow(new DataAccessResourceFailureException("sensitive driver detail"));

        ResponseEntity<?> response = controller.list("35");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body)
                .containsEntry("error", "mssql_unavailable")
                .containsEntry("op", "projectOptions")
                .doesNotContainValue("sensitive driver detail");
    }
}
