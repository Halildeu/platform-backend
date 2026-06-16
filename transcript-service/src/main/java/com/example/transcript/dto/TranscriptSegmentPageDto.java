package com.example.transcript.dto;

import java.util.List;
import org.springframework.data.domain.Page;

/**
 * Paged transcript-segment result for the list + search endpoints.
 */
public record TranscriptSegmentPageDto(
        List<TranscriptSegmentDto> content,
        long totalElements,
        int page,
        int size
) {

    public static TranscriptSegmentPageDto from(Page<TranscriptSegmentDto> page) {
        return new TranscriptSegmentPageDto(
                page.getContent(),
                page.getTotalElements(),
                page.getNumber(),
                page.getSize()
        );
    }
}
