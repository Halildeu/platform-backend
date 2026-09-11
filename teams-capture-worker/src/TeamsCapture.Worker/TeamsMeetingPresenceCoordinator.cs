namespace TeamsCapture.Worker;

/// <summary>
/// Owns only the Teams/Calendar presence of the bot. Audio capture stays on an
/// authorized mobile or desktop recorder, while meeting-service remains the
/// single source of persisted transcript and analysis results.
/// </summary>
public sealed class TeamsMeetingPresenceCoordinator
{
    public async Task<MeetingPresenceResult> JoinAsync(
        MeetingPresenceCommand command,
        ITeamsMeetingPresenceClient teamsClient,
        CancellationToken cancellationToken)
    {
        if (command.MeetingId == Guid.Empty || string.IsNullOrWhiteSpace(command.CalendarEventId))
        {
            return MeetingPresenceResult.Rejected("invalid_meeting_reference");
        }

        var result = await teamsClient.JoinAsync(command, cancellationToken).ConfigureAwait(false);
        return result is null
            ? MeetingPresenceResult.Rejected("teams_join_not_confirmed")
            : MeetingPresenceResult.Succeeded(result.ConversationId);
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
    string CorrelationId);

public sealed record TeamsJoinReceipt(string ConversationId);

public sealed record MeetingPresenceResult(bool Joined, string? ConversationId, string? FailureCode)
{
    public static MeetingPresenceResult Rejected(string failureCode) => new(false, null, failureCode);

    public static MeetingPresenceResult Succeeded(string conversationId) => new(true, conversationId, null);
}

public interface ITeamsMeetingPresenceClient
{
    Task<TeamsJoinReceipt?> JoinAsync(MeetingPresenceCommand command, CancellationToken cancellationToken);
}
