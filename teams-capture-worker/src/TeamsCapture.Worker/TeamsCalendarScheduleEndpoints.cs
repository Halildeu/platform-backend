using Microsoft.Extensions.Options;

namespace TeamsCapture.Worker;

public static class TeamsCalendarScheduleEndpoints
{
    public static void MapTeamsCalendarSchedules(this WebApplication app, Func<HttpContext, string, bool> authorized)
    {
        app.MapPost("/api/teams/meetings/{meetingId:guid}/calendar-schedule", async (Guid meetingId,
            CalendarScheduleRequest request, HttpContext context, IOptions<TeamsCaptureOptions> settings,
            TeamsCalendarScheduleStore store, ITeamsCalendarClient calendar, TimeProvider clock, CancellationToken token) =>
        {
            context.Response.Headers.CacheControl = "no-store";
            if (!authorized(context, settings.Value.ControlApiKey ?? "")) return Results.Unauthorized();
            if (!settings.Value.IsReadyForCalendarScheduling()) return Results.StatusCode(503);
            var selection = new CalendarSelection(meetingId, request.OrganizerId, request.EventId, request.CorrelationId);
            if (!selection.IsValid()) return Results.BadRequest(new { code = "invalid_calendar_selection" });
            if (!settings.Value.CalendarOrganizerIds.Contains(selection.OrganizerId)) return Results.StatusCode(403);
            if (store.Read(meetingId) is { } existing)
                return existing.Selection == selection ? Results.Ok(View(existing))
                    : Results.Conflict(new { code = "calendar_selection_conflict" });
            var current = await calendar.ReadAsync(selection.OrganizerId, selection.EventId, token);
            if (current is null) return Results.Problem(statusCode: 502, title: "calendar_read_unavailable");
            var now = clock.GetUtcNow();
            if (current.Cancelled || current.EndsAt <= now || now - current.StartsAt > TimeSpan.FromMinutes(5)
                || current.StartsAt - now > TimeSpan.FromDays(90))
                return Results.Conflict(new { code = "event_not_schedulable" });
            token.ThrowIfCancellationRequested();
            var proposed = new CalendarSchedule(selection, "pending", current.StartsAt, current.EndsAt, now, now);
            if (!store.Add(proposed)) return Results.Conflict(new { code = "schedule_conflict_or_capacity" });
            return Results.Accepted(value: View(store.Read(meetingId)!));
        });

        app.MapGet("/api/teams/meetings/{meetingId:guid}/calendar-schedule", (Guid meetingId,
            HttpContext context, IOptions<TeamsCaptureOptions> settings, TeamsCalendarScheduleStore store) =>
        {
            context.Response.Headers.CacheControl = "no-store";
            if (!authorized(context, settings.Value.ControlApiKey ?? "")) return Results.Unauthorized();
            if (!settings.Value.IsReadyForCalendarScheduling()) return Results.StatusCode(503);
            return store.Read(meetingId) is { } item ? Results.Ok(View(item)) : Results.NotFound();
        });

        app.MapDelete("/api/teams/meetings/{meetingId:guid}/calendar-schedule", (Guid meetingId,
            HttpContext context, IOptions<TeamsCaptureOptions> settings, TeamsCalendarScheduleStore store, TimeProvider clock) =>
        {
            context.Response.Headers.CacheControl = "no-store";
            if (!authorized(context, settings.Value.ControlApiKey ?? "")) return Results.Unauthorized();
            if (!settings.Value.IsReadyForCalendarScheduling()) return Results.StatusCode(503);
            var item = store.Read(meetingId);
            if (item is null) return Results.NotFound();
            if (item.State == "cancelled") return Results.NoContent();
            if (item.State != "pending") return Results.Conflict(new { code = "schedule_already_dispatched_or_terminal" });
            return store.Replace(item, item with { State = "cancelled", UpdatedAt = clock.GetUtcNow(), Failure = null })
                ? Results.NoContent() : Results.Conflict(new { code = "schedule_changed_retry_read" });
        });
    }

    // Outlook event identifiers, join URLs and organizer identities remain internal metadata.
    private static object View(CalendarSchedule item) => new
    { meetingId = item.Selection.MeetingId, item.State, item.StartsAt, item.EndsAt, item.CallId, item.Failure };
}

public sealed record CalendarScheduleRequest(Guid OrganizerId, string EventId, string CorrelationId);
