package com.example.transcript.directstt;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.example.transcript.repository.TranscriptSegmentRepository;
import com.example.transcript.repository.TranscriptSessionAssociationRepository;
import com.example.transcript.service.SessionErasureFence;
import com.example.transcript.service.SessionErasureFence.SessionErasedException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class TranscriptSessionAssociationStoreErasureTest {

    @Test
    void absentAssociationCannotBeInsertedAfterSourceWasErased() {
        TranscriptSessionAssociationRepository associations =
                mock(TranscriptSessionAssociationRepository.class);
        SessionErasureFence fence = mock(SessionErasureFence.class);
        TranscriptSessionAssociationStore store = new TranscriptSessionAssociationStore(
                associations, mock(TranscriptSegmentRepository.class), fence);
        UUID tenant = UUID.randomUUID();
        UUID meeting = UUID.randomUUID();
        doThrow(new SessionErasedException()).when(fence)
                .rejectSourceErased(tenant, meeting, "REC-42");

        assertThatThrownBy(() -> store.ensurePending(
                UUID.randomUUID(), tenant, meeting, "REC-42", Instant.now()))
                .isInstanceOf(SessionErasedException.class);

        verify(associations, never()).insertPendingIfAbsent(
                any(), any(), any(), any(), any(), any());
    }
}
