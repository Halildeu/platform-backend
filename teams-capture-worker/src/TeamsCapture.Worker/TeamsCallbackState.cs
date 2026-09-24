using System.Text.Json;
using Microsoft.Extensions.Options;

namespace TeamsCapture.Worker;

/// <summary>Bounded call status persisted without audio, transcript or tokens.</summary>
public sealed class TeamsCallbackState
{
    private readonly object gate = new();
    private Dictionary<string, CallState> calls = new(StringComparer.Ordinal);
    private Dictionary<Guid, JoinAttempt> joins = new();
    private readonly DurableTeamsSnapshot? store;

    public TeamsCallbackState() { }

    public TeamsCallbackState(IOptions<TeamsCaptureOptions> options)
    {
        if (!string.IsNullOrWhiteSpace(options.Value.CallStateFilePath))
        {
            store = new(options.Value.CallStateFilePath, "calls");
            Load();
        }
    }

    public bool Register(string id, Guid meetingId = default)
    {
        if (!ValidCallId(id)) return false;
        lock (gate)
        {
            if (calls.TryGetValue(id, out var existing)) return existing.MeetingId == meetingId;
            if (calls.Count >= 1000) return false;
            var snapshot = new Dictionary<string, CallState>(calls, StringComparer.Ordinal);
            snapshot.Add(id, new CallState(meetingId, "establishing", DateTimeOffset.UtcNow));
            Persist(snapshot);
            calls = snapshot;
            return true;
        }
    }

    public static bool ValidCallId(string? id) => id is { Length: > 0 and <= 128 }
        && id.All(c => char.IsAsciiLetterOrDigit(c) || c == '-');

    // Persist before sending a remote POST. An ambiguous response never permits a blind retry.
    public JoinReservation ReserveJoin(Guid meetingId, string calendarEventId)
    {
        lock (gate)
        {
            if (joins.TryGetValue(meetingId, out var existing))
                return new(false, existing.CalendarEventId == calendarEventId ? "existing" : "conflict", existing.CallId);
            var legacy = calls.FirstOrDefault(call => call.Value.MeetingId == meetingId);
            if (legacy.Key is not null) return new(false, "existing", legacy.Key);
            if (joins.Count >= 1000 || calls.Count + joins.Values.Count(join => join.CallId is null) >= 1000)
                return new(false, "capacity_exhausted", null);
            var snapshot = new Dictionary<Guid, JoinAttempt>(joins)
            {
                [meetingId] = new(calendarEventId, null, DateTimeOffset.UtcNow)
            };
            Persist(calls, snapshot);
            joins = snapshot;
            return new(true, "reserved", null);
        }
    }

    public JoinAttempt? ReadJoin(Guid meetingId) { lock (gate) return joins.GetValueOrDefault(meetingId); }

    public void ReleaseUnsentJoin(Guid meetingId)
    {
        lock (gate)
        {
            if (!joins.TryGetValue(meetingId, out var attempt) || attempt.CallId is not null) return;
            var snapshot = new Dictionary<Guid, JoinAttempt>(joins);
            snapshot.Remove(meetingId);
            Persist(calls, snapshot);
            joins = snapshot;
        }
    }

    public bool CompleteJoin(Guid meetingId, string id)
    {
        if (!ValidCallId(id)) return false;
        lock (gate)
        {
            if (!joins.TryGetValue(meetingId, out var attempt)) return false;
            if (calls.TryGetValue(id, out var existing) && existing.MeetingId != meetingId) return false;
            if (attempt.CallId is not null && attempt.CallId != id) return false;
            var callSnapshot = new Dictionary<string, CallState>(calls, StringComparer.Ordinal);
            callSnapshot.TryAdd(id, new(meetingId, "establishing", DateTimeOffset.UtcNow));
            var joinSnapshot = new Dictionary<Guid, JoinAttempt>(joins) { [meetingId] = attempt with { CallId = id } };
            Persist(callSnapshot, joinSnapshot);
            calls = callSnapshot;
            joins = joinSnapshot;
            return true;
        }
    }

    public string[] ActiveCallIds()
    {
        lock (gate) return calls.Where(call => call.Value.MeetingId != Guid.Empty
            && call.Value.State is "establishing" or "established").Select(call => call.Key).ToArray();
    }

    public bool MarkTerminated(string callId) => Apply(JsonSerializer.SerializeToElement(new
    {
        value = new[] { new { resourceUrl = "/communications/calls/" + callId, resourceData = new { state = "terminated" } } }
    }));

    public Guid[] ExpiredCompletedMeetings(DateTimeOffset cutoff)
    {
        lock (gate) return calls.Values.Where(call => call.MeetingId != Guid.Empty
            && call.State == "terminated" && call.UpdatedAt < cutoff)
            .Select(call => call.MeetingId).Distinct()
            .Where(meeting => !calls.Values.Any(call => call.MeetingId == meeting
                && (call.State != "terminated" || call.UpdatedAt >= cutoff))).ToArray();
    }

    public void RemoveExpiredCompleted(DateTimeOffset cutoff)
    {
        lock (gate)
        {
            var expired = ExpiredCompletedMeetings(cutoff).ToHashSet();
            if (expired.Count == 0) return;
            var callSnapshot = calls.Where(call => !expired.Contains(call.Value.MeetingId))
                .ToDictionary(call => call.Key, call => call.Value, StringComparer.Ordinal);
            var joinSnapshot = joins.Where(join => !expired.Contains(join.Key)).ToDictionary(join => join.Key, join => join.Value);
            Persist(callSnapshot, joinSnapshot);
            calls = callSnapshot;
            joins = joinSnapshot;
        }
    }

    public string? Read(string id) { lock (gate) return calls.GetValueOrDefault(id)?.State; }

    public Guid? ReadMeetingId(string id)
    {
        lock (gate) return calls.TryGetValue(id, out var value) && value.MeetingId != Guid.Empty
            ? value.MeetingId : null;
    }

    public bool Apply(JsonElement payload)
    {
        if (payload.ValueKind != JsonValueKind.Object || !payload.TryGetProperty("value", out var events)
            || events.ValueKind != JsonValueKind.Array || events.GetArrayLength() is < 1 or > 100) return false;
        var updates = new List<(string Id, string? State)>();
        foreach (var item in events.EnumerateArray())
        {
            if (item.ValueKind != JsonValueKind.Object || !item.TryGetProperty("resourceData", out var data)
                || !item.TryGetProperty("resourceUrl", out var resource) || resource.ValueKind != JsonValueKind.String)
                return false;
            const string prefix = "/communications/calls/";
            var path = resource.GetString()!;
            if (!path.StartsWith(prefix, StringComparison.Ordinal)) return false;
            var roster = path.EndsWith("/participants", StringComparison.Ordinal);
            var id = roster ? path[prefix.Length..^"/participants".Length] : path[prefix.Length..];
            if (!ValidCallId(id)) return false;
            if (roster)
            {
                // The REST roster endpoint is the source for identity metadata. Acknowledge
                // Graph's auxiliary notification without retaining names or claiming speech.
                if (data.ValueKind != JsonValueKind.Array || data.GetArrayLength() > 1000
                    || data.EnumerateArray().Any(entry => entry.ValueKind != JsonValueKind.Object)) return false;
                updates.Add((id, null));
                continue;
            }
            if (data.ValueKind != JsonValueKind.Object || !data.TryGetProperty("state", out var state)
                || state.ValueKind != JsonValueKind.String) return false;
            var next = state.GetString()!;
            if (next is not ("establishing" or "established" or "terminating" or "terminated")) return false;
            updates.Add((id, next));
        }
        lock (gate)
        {
            // A callback racing join registration must be retried, never silently discarded.
            if (updates.Any(update => !calls.ContainsKey(update.Id))) return false;
            var snapshot = new Dictionary<string, CallState>(calls, StringComparer.Ordinal);
            var changed = false;
            foreach (var update in updates)
                if (update.State is not null && Rank(update.State) > Rank(snapshot[update.Id].State))
                {
                    snapshot[update.Id] = snapshot[update.Id] with { State = update.State, UpdatedAt = DateTimeOffset.UtcNow };
                    changed = true;
                }
            if (changed) { Persist(snapshot); calls = snapshot; }
            return true;
        }
    }
    private static int Rank(string state) => state switch { "establishing" => 0, "established" => 1, "terminating" => 2, _ => 3 };

    private void Load()
    {
        var payload = store?.Read();
        if (payload is null) return;
        using var document = JsonDocument.Parse(payload);
        Dictionary<string, CallState>? restored;
        if (document.RootElement.TryGetProperty("Calls", out var storedCalls)
            && document.RootElement.TryGetProperty("Joins", out var storedJoins))
        {
            restored = storedCalls.Deserialize<Dictionary<string, CallState>>();
            joins = storedJoins.Deserialize<Dictionary<Guid, JoinAttempt>>()
                ?? throw new InvalidDataException("Invalid Teams join state file.");
            if (joins.Count > 1000 || joins.Any(join => join.Key == Guid.Empty || join.Value is null
                || string.IsNullOrWhiteSpace(join.Value.CalendarEventId)
                || (join.Value.CallId is not null && !ValidCallId(join.Value.CallId))))
                throw new InvalidDataException("Invalid Teams join state file.");
        }
        else restored = document.RootElement.Deserialize<Dictionary<string, CallState>>();
        if (restored is null || restored.Count > 1000) throw new InvalidDataException("Invalid Teams call state file.");
        foreach (var item in restored)
        {
            if (!ValidCallId(item.Key) || item.Value is null
                || item.Value.State is not ("establishing" or "established" or "terminating" or "terminated"))
                throw new InvalidDataException("Invalid Teams call state file.");
            calls[item.Key] = item.Value;
        }
        if (joins.Any(join => join.Value.CallId is not null
            && (!calls.TryGetValue(join.Value.CallId, out var call) || call.MeetingId != join.Key)))
            throw new InvalidDataException("Inconsistent Teams join state file.");
    }

    private void Persist(Dictionary<string, CallState> snapshot, Dictionary<Guid, JoinAttempt>? joinSnapshot = null)
    {
        store?.Write(JsonSerializer.Serialize(new { Calls = snapshot, Joins = joinSnapshot ?? joins }));
    }

    private sealed record CallState(Guid MeetingId, string State, DateTimeOffset UpdatedAt);
}

public sealed record JoinReservation(bool Reserved, string Status, string? CallId);
public sealed record JoinAttempt(string CalendarEventId, string? CallId, DateTimeOffset RequestedAt);
