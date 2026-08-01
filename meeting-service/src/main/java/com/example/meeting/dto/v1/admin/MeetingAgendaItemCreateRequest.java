package com.example.meeting.dto.v1.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/** Request for adding an ordered item to a meeting agenda. */
public record MeetingAgendaItemCreateRequest(
        @NotNull @PositiveOrZero Integer position,
        @NotBlank @Size(max = 512) String title,
        @Size(max = 4000) String detail,
        @Size(max = 255) String ownerSubject,
        @Positive Integer plannedDurationMinutes) {
}
