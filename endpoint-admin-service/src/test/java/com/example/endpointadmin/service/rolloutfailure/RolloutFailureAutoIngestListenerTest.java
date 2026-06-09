package com.example.endpointadmin.service.rolloutfailure;

import com.example.endpointadmin.model.CommandType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The AFTER_COMMIT listener must swallow every service exception (advisory). */
class RolloutFailureAutoIngestListenerTest {

    private final RolloutFailureAutoIngestService service = mock(RolloutFailureAutoIngestService.class);
    private final RolloutFailureAutoIngestListener listener = new RolloutFailureAutoIngestListener(service);

    private CommandResultFailedEvent event() {
        return new CommandResultFailedEvent(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), CommandType.INSTALL_SOFTWARE);
    }

    @Test
    void serviceExceptionIsSwallowed() {
        when(service.ingest(any())).thenThrow(new IllegalStateException("boom"));
        CommandResultFailedEvent e = event();
        assertThatCode(() -> listener.onCommandResultFailed(e)).doesNotThrowAnyException();
        verify(service).ingest(e);
    }

    @Test
    void successfulIngestDoesNotThrow() {
        when(service.ingest(any())).thenReturn(true);
        assertThatCode(() -> listener.onCommandResultFailed(event())).doesNotThrowAnyException();
    }
}
