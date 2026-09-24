using Microsoft.Extensions.Options;

namespace TeamsCapture.Worker;

public sealed class TeamsCallMaintenanceService(TeamsCallbackState state, DurableTeamsCalendarMeetingResolver calendar,
    ITeamsCallLifecycleClient lifecycle, IOptions<TeamsCaptureOptions> settings, TimeProvider clock,
    ILogger<TeamsCallMaintenanceService> logger) : BackgroundService
{
    private readonly Dictionary<string, DateTimeOffset> nextAttempt = new(StringComparer.Ordinal);

    protected override async Task ExecuteAsync(CancellationToken stoppingToken)
    {
        using var timer = new PeriodicTimer(TimeSpan.FromMinutes(1), clock);
        while (!stoppingToken.IsCancellationRequested)
        {
            try { await MaintainOnceAsync(stoppingToken).ConfigureAwait(false); }
            catch (OperationCanceledException) when (stoppingToken.IsCancellationRequested) { break; }
            catch (Exception error) when (error is IOException or UnauthorizedAccessException)
            {
                logger.LogError("Teams call maintenance could not persist state; operator inspection required.");
            }
            if (!await timer.WaitForNextTickAsync(stoppingToken).ConfigureAwait(false)) break;
        }
    }

    public async Task MaintainOnceAsync(CancellationToken cancellationToken)
    {
        if (!settings.Value.IsReadyForRegistration()) return;
        var now = clock.GetUtcNow();
        if (settings.Value.CompletedCallRetentionHours is { } retention)
        {
            try
            {
                var cutoff = now.AddHours(-retention);
                calendar.RemoveCompleted(state.ExpiredCompletedMeetings(cutoff));
                state.RemoveExpiredCompleted(cutoff);
            }
            catch (Exception error) when (error is IOException or UnauthorizedAccessException)
            {
                logger.LogError("Teams retention could not persist cleanup; active call maintenance will continue.");
            }
        }
        var active = state.ActiveCallIds().ToHashSet(StringComparer.Ordinal);
        foreach (var id in nextAttempt.Keys.Where(id => !active.Contains(id)).ToArray()) nextAttempt.Remove(id);
        // Bound concurrency so a slow call cannot starve all other heartbeats.
        var due = active.Where(id => !nextAttempt.TryGetValue(id, out var next) || next <= now).ToArray();
        var results = new System.Collections.Concurrent.ConcurrentBag<(string Id, TeamsCallOperationResult Result)>();
        await Parallel.ForEachAsync(due, new ParallelOptions { MaxDegreeOfParallelism = 32, CancellationToken = cancellationToken },
            async (id, token) =>
            {
                if (state.Read(id) is "establishing" or "established")
                    results.Add((id, await lifecycle.KeepAliveAsync(id, token).ConfigureAwait(false)));
            }).ConfigureAwait(false);
        foreach (var (id, result) in results)
        {
            if (result == TeamsCallOperationResult.Ended) { state.MarkTerminated(id); nextAttempt.Remove(id); }
            else
            {
                nextAttempt[id] = clock.GetUtcNow().AddMinutes(result == TeamsCallOperationResult.Succeeded ? 15 : 1);
                if (result == TeamsCallOperationResult.Unconfirmed)
                    logger.LogWarning("Teams keepAlive was not confirmed; it will be retried. Live call continuity is unverified.");
            }
        }
    }
}
