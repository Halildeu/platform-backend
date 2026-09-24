using Microsoft.Extensions.Options;

namespace TeamsCapture.Worker;

public sealed class TeamsCalendarSchedulingService(TeamsCalendarScheduleStore store, ITeamsCalendarClient calendar,
    TeamsCallbackState calls, DurableTeamsCalendarMeetingResolver resolver, TeamsMeetingPresenceCoordinator coordinator,
    ITeamsMeetingPresenceClient presence, IOptions<TeamsCaptureOptions> settings, TimeProvider clock,
    ILogger<TeamsCalendarSchedulingService> logger) : BackgroundService
{
    private readonly SemaphoreSlim tickGate = new(1, 1);

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        using var timer = new PeriodicTimer(TimeSpan.FromSeconds(5), clock);
        while (!stoppingToken.IsCancellationRequested)
        {
            try { await TickAsync(stoppingToken); }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested) { break; }
            catch (Exception error) when (error is IOException or UnauthorizedAccessException)
            { logger.LogError("Teams calendar scheduling state could not be committed; no new join is confirmed."); }
            if (!await timer.WaitForNextTickAsync(stoppingToken)) break;
        }
    }

    public async Task TickAsync(CancellationToken cancellationToken)
    {
        if (!settings.Value.IsReadyForCalendarScheduling()) return;
        await tickGate.WaitAsync(cancellationToken);
        try
        {
            if (settings.Value.CompletedCallRetentionHours is { } hours)
                store.RemoveTerminalBefore(clock.GetUtcNow().AddHours(-hours));
            await Parallel.ForEachAsync(store.Due(clock.GetUtcNow()), new ParallelOptions
                { MaxDegreeOfParallelism = 4, CancellationToken = cancellationToken }, ProcessAsync);
        }
        finally { tickGate.Release(); }
    }

    private async ValueTask ProcessAsync(CalendarSchedule item, CancellationToken cancellationToken)
    {
        var now = clock.GetUtcNow();
        if (item.State == "dispatching")
        {
            // Recovery never blindly resends a POST whose outcome may already exist at Microsoft.
            var attempt = calls.ReadJoin(item.Selection.MeetingId);
            var callId = attempt?.CalendarEventId == item.Selection.Reference ? attempt.CallId : null;
            store.Replace(item, item with { State = callId is null ? "failed" : "joined", CallId = callId,
                Failure = callId is null ? "interrupted_join_requires_reconciliation" : null, UpdatedAt = now });
            return;
        }
        if (!settings.Value.CalendarOrganizerIds.Contains(item.Selection.OrganizerId))
        { Finish(item, "failed", "organizer_not_allowed"); return; }
        var current = await calendar.ReadAsync(item.Selection.OrganizerId, item.Selection.EventId, cancellationToken);
        if (current is null) { Defer(item, "calendar_read_unavailable"); return; }
        if (current.Cancelled) { Finish(item, "cancelled", null); return; }
        now = clock.GetUtcNow();
        if (current.StartsAt > now)
        {
            store.Replace(item, item with { StartsAt = current.StartsAt, EndsAt = current.EndsAt, Failure = null,
                NextCheckAt = current.StartsAt < now.AddSeconds(30) ? current.StartsAt : now.AddSeconds(30), UpdatedAt = now });
            return;
        }
        if (!CanJoin(current, now)) { Finish(item, "expired", "join_window_elapsed"); return; }
        var meeting = await calendar.ResolveMeetingAsync(item.Selection.OrganizerId, current.JoinUrl!, cancellationToken);
        if (meeting is null) { Defer(item, "online_meeting_unavailable"); return; }
        // Re-read after onlineMeeting resolution to catch cancellation/time/link changes during lookup.
        var latest = await calendar.ReadAsync(item.Selection.OrganizerId, item.Selection.EventId, cancellationToken);
        if (latest is null) { Defer(item, "calendar_read_unavailable"); return; }
        if (latest.Cancelled) { Finish(item, "cancelled", null); return; }
        if (latest != current || !CanJoin(latest, clock.GetUtcNow())) { Defer(item, "calendar_changed"); return; }
        cancellationToken.ThrowIfCancellationRequested();
        var dispatching = item with { State = "dispatching", StartsAt = latest.StartsAt, EndsAt = latest.EndsAt,
            UpdatedAt = clock.GetUtcNow(), Failure = null };
        // This durable compare-and-swap is the cancellation/dispatch boundary.
        if (!store.Replace(item, dispatching)) return;
        if (!resolver.Register(item.Selection.MeetingId, item.Selection.Reference, meeting))
        { Finish(dispatching, "failed", "calendar_reference_conflict"); return; }
        var result = await coordinator.JoinAsync(new(item.Selection.MeetingId, item.Selection.Reference,
            item.Selection.CorrelationId), presence, cancellationToken);
        store.Replace(dispatching, dispatching with { State = result.Joined ? "joined" : "failed",
            CallId = result.CallId, Failure = result.FailureCode, UpdatedAt = clock.GetUtcNow() });
    }

    private static bool CanJoin(TeamsCalendarEvent item, DateTimeOffset now) => !item.Cancelled
        && item.StartsAt <= now && item.EndsAt > now && now - item.StartsAt <= TimeSpan.FromMinutes(5);
    private void Defer(CalendarSchedule item, string reason) => store.Replace(item,
        item with { NextCheckAt = clock.GetUtcNow().AddSeconds(30), Failure = reason, UpdatedAt = clock.GetUtcNow() });
    private void Finish(CalendarSchedule item, string state, string? failure) => store.Replace(item,
        item with { State = state, Failure = failure, UpdatedAt = clock.GetUtcNow() });
}
