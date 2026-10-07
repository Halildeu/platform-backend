namespace TeamsCapture.Media;

public sealed record MediaCallKey(Guid TenantId, Guid MeetingId, string CallId, Guid MediaSessionId)
{
    internal void Validate()
    {
        if (TenantId == Guid.Empty || MeetingId == Guid.Empty || MediaSessionId == Guid.Empty
            || !ValidText(CallId, 128)) throw new ArgumentException("Invalid media call scope.");
    }

    internal static bool ValidText(string? value, int max) => !string.IsNullOrWhiteSpace(value)
        && value.Length <= max && !value.Any(char.IsControl);
}

public sealed record SpeakerIdentity(string ParticipantId, string? UserId, string? DisplayName);
public sealed record ParticipantAudioSources(SpeakerIdentity Identity, IReadOnlyList<uint> SourceIds,
    bool IsInLobby = false);
public sealed record SpeakerAttribution(string Status, SpeakerIdentity? Speaker = null);

/// <summary>
/// Full SDK roster observations in the receiving media clock (100-ns ticks),
/// scoped to one call and media-session lifetime. REST wall-clock snapshots and
/// OriginalSenderTimestamp must not be passed as this clock. No identity inference.
/// </summary>
public sealed class TemporalSpeakerMap
{
    private const int MaxSources = 1000;
    private const int MaxSnapshots = 128;
    private readonly object sync = new();
    private readonly List<Snapshot> history = [];
    private readonly Dictionary<uint, (string Participant, string? User)> firstOwners = [];
    private readonly HashSet<uint> reused = [];
    private readonly long maximumAgeTicks;
    private bool invalidated;
    public MediaCallKey Key { get; }

    public TemporalSpeakerMap(MediaCallKey key, TimeSpan maximumRosterAge)
    {
        key.Validate();
        if (maximumRosterAge <= TimeSpan.Zero || maximumRosterAge > TimeSpan.FromMinutes(1))
            throw new ArgumentOutOfRangeException(nameof(maximumRosterAge));
        Key = key;
        maximumAgeTicks = maximumRosterAge.Ticks;
    }

    public bool ApplySnapshot(MediaCallKey key, long revision, long observedAt,
        IReadOnlyList<ParticipantAudioSources> participants)
    {
        lock (sync)
        {
            if (invalidated || key != Key) return false;
            if (revision <= 0 || observedAt < 0 || participants is null || participants.Count > MaxSources
                || history.Count > 0 && (revision <= history[^1].Revision || observedAt <= history[^1].At))
                return Invalidate();
            var sources = new Dictionary<uint, SpeakerIdentity?>();
            var participantIds = new HashSet<string>(StringComparer.Ordinal);
            foreach (var participant in participants)
            {
                if (participant?.Identity is not { } identity || participant.SourceIds is null
                    || participant.SourceIds.Count > MaxSources
                    || !MediaCallKey.ValidText(identity.ParticipantId, 256)
                    || identity.UserId is not null && !MediaCallKey.ValidText(identity.UserId, 256)
                    || identity.DisplayName is not null && !MediaCallKey.ValidText(identity.DisplayName, 256)
                    || !participantIds.Add(identity.ParticipantId)) return Invalidate();
                foreach (var source in participant.SourceIds)
                {
                    if (source == 0 || sources.Count >= MaxSources) return Invalidate();
                    var candidate = participant.IsInLobby ? null : identity;
                    // Duplicate ownership is unknown, even when display names match.
                    if (!sources.TryAdd(source, candidate)) sources[source] = null;
                    if (firstOwners.TryGetValue(source, out var owner))
                    {
                        if (owner != (identity.ParticipantId, identity.UserId)) reused.Add(source);
                    }
                    else
                    {
                        if (firstOwners.Count >= MaxSources) return Invalidate();
                        firstOwners.Add(source, (identity.ParticipantId, identity.UserId));
                    }
                }
            }
            history.Add(new Snapshot(revision, observedAt, sources));
            if (history.Count > MaxSnapshots) history.RemoveAt(0);
            return true;
        }
    }

    public SpeakerAttribution Resolve(MediaCallKey key, uint source, long receivedAt, long durationTicks)
    {
        lock (sync)
        {
            if (key != Key) return new("wrong_session");
            if (invalidated || durationTicks <= 0 || receivedAt < 0 || receivedAt > long.MaxValue - durationTicks)
                return new("roster_unavailable");
            if (reused.Contains(source)) return new("source_reused");
            for (var index = history.Count - 1; index >= 0; index--)
            {
                var snapshot = history[index];
                if (snapshot.At > receivedAt) continue;
                var end = receivedAt + durationTicks;
                if (index + 1 < history.Count && end > history[index + 1].At)
                    return new("roster_transition");
                if (end - snapshot.At > maximumAgeTicks) return new("roster_expired");
                return snapshot.Sources.TryGetValue(source, out var identity) && identity is not null
                    ? new("matched", identity) : new("unknown_source");
            }
            return new("roster_unavailable");
        }
    }

    public void Clear() { lock (sync) Invalidate(); }

    private bool Invalidate()
    {
        invalidated = true;
        history.Clear();
        firstOwners.Clear();
        reused.Clear();
        return false;
    }

    private sealed record Snapshot(long Revision, long At, Dictionary<uint, SpeakerIdentity?> Sources);
}
