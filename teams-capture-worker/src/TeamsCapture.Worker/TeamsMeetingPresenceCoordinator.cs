namespace TeamsCapture.Worker;

/// <summary>
/// Owns only the Teams/Calendar presence of the bot. Audio capture stays on an
/// authorized mobile or desktop recorder, while meeting-service remains the
/// single source of persisted transcript and analysis results.
/// </summary>
public sealed class TeamsMeetingPresenceCoordinator(TeamsCallbackState state, ITeamsCallLifecycleClient lifecycle)
{
    public async Task<MeetingPresenceResult> JoinAsync(
        MeetingPresenceCommand command,
        ITeamsMeetingPresenceClient teamsClient,
        CancellationToken cancellationToken)
    {
        if (!command.IsValid())
        {
            return MeetingPresenceResult.Rejected("invalid_meeting_reference");
        }

        if (cancellationToken.IsCancellationRequested) return MeetingPresenceResult.Rejected("join_not_sent");
        var reservation = state.ReserveJoin(command.MeetingId, command.CalendarEventId);
        if (!reservation.Reserved)
        {
            if (reservation.Status != "existing") return MeetingPresenceResult.Rejected(reservation.Status);
            return reservation.CallId is not null && state.Read(reservation.CallId) is "establishing" or "established"
                ? MeetingPresenceResult.Succeeded(reservation.CallId)
                : MeetingPresenceResult.Rejected(reservation.CallId is null ? "join_outcome_unconfirmed" : "call_already_ended");
        }

        TeamsJoinReceipt? result;
        try { result = await teamsClient.JoinAsync(command, cancellationToken).ConfigureAwait(false); }
        catch (TeamsScheduleNotAuthorizedException error)
        {
            state.ReleaseUnsentJoin(command.MeetingId);
            return MeetingPresenceResult.Rejected(error.Code);
        }
        catch (TeamsJoinNotCreatedException)
        {
            state.ReleaseUnsentJoin(command.MeetingId);
            return MeetingPresenceResult.Rejected("join_not_created");
        }
        catch (Exception error) when (error is HttpRequestException or OperationCanceledException)
        {
            // A timed-out POST may already have created a remote call. Keep the reservation.
            return MeetingPresenceResult.Rejected("join_outcome_unconfirmed");
        }
        if (result is null) return MeetingPresenceResult.Rejected("join_outcome_unconfirmed");
        try
        {
            if (state.CompleteJoin(command.MeetingId, result.CallId)) return MeetingPresenceResult.Succeeded(result.CallId);
        }
        catch (Exception error) when (error is IOException or UnauthorizedAccessException) { }

        // The remote call exists but local ownership could not be committed. Compensate
        // independently of an aborted HTTP request; never start another call on retry.
        if (state.ReadMeetingId(result.CallId) is { } owner && owner != command.MeetingId)
            return MeetingPresenceResult.Rejected("join_receipt_ownership_conflict");
        using var cleanup = new CancellationTokenSource(TimeSpan.FromSeconds(20));
        var outcome = await lifecycle.LeaveAsync(result.CallId, cleanup.Token).ConfigureAwait(false);
        return MeetingPresenceResult.Rejected(outcome is TeamsCallOperationResult.Succeeded or TeamsCallOperationResult.Ended
            ? "join_persistence_failed_call_removed" : "join_persistence_failed_cleanup_unconfirmed");
    }
}

/// <summary>
/// The canonical platform meeting UUID is the only join-to-analysis link. The
/// calendar event identifier is opaque and must never be used as a transcript
/// or result identifier.
/// </summary>
public sealed record MeetingPresenceCommand(
    Guid MeetingId,
    string CalendarEventId,
    string CorrelationId)
{
    public bool IsValid() => MeetingId != Guid.Empty
        && DurableTeamsCalendarMeetingResolver.ValidReference(CalendarEventId)
        && CorrelationId is { Length: > 0 and <= 128 }
        && CorrelationId.All(c => char.IsAsciiLetterOrDigit(c) || c is '-' or '_' or '.');
}

public sealed record TeamsJoinReceipt(string CallId);

// Only the transport can establish that no call was created: pre-dispatch failure
// or a definite Graph rejection. Timeouts after dispatch must never use this type.
public sealed class TeamsJoinNotCreatedException : Exception { }

public sealed record MeetingPresenceResult(bool Joined, string? CallId, string? FailureCode)
{
    public static MeetingPresenceResult Rejected(string failureCode) => new(false, null, failureCode);

    public static MeetingPresenceResult Succeeded(string callId) => new(true, callId, null);
}

public interface ITeamsMeetingPresenceClient
{
    Task<TeamsJoinReceipt?> JoinAsync(MeetingPresenceCommand command, CancellationToken cancellationToken);
}
