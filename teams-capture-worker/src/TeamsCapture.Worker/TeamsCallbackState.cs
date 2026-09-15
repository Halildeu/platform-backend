using System.Text.Json;
using Microsoft.Extensions.Options;

namespace TeamsCapture.Worker;

/// <summary>Bounded call status persisted without audio, transcript or tokens.</summary>
public sealed class TeamsCallbackState
{
    private readonly object gate = new();
    private Dictionary<string, CallState> calls = new(StringComparer.Ordinal);
    private readonly string? stateFilePath;

    public TeamsCallbackState() { }

    public TeamsCallbackState(IOptions<TeamsCaptureOptions> options)
    {
        stateFilePath = options.Value.CallStateFilePath;
        if (!string.IsNullOrWhiteSpace(stateFilePath)) Load();
    }

    public bool Register(string id, Guid meetingId = default)
    {
        lock (gate)
        {
            if (calls.ContainsKey(id)) return true;
            if (calls.Count >= 1000) return false;
            var snapshot = new Dictionary<string, CallState>(calls, StringComparer.Ordinal);
            snapshot.Add(id, new CallState(meetingId, "establishing", DateTimeOffset.UtcNow));
            Persist(snapshot);
            calls = snapshot;
            return true;
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
        var updates = new List<(string Id, string State)>();
        foreach (var item in events.EnumerateArray())
        {
            if (item.ValueKind != JsonValueKind.Object || !item.TryGetProperty("resourceData", out var data)
                || data.ValueKind != JsonValueKind.Object
                || !data.TryGetProperty("state", out var state) || state.ValueKind != JsonValueKind.String
                || !item.TryGetProperty("resourceUrl", out var resource) || resource.ValueKind != JsonValueKind.String)
                return false;
            const string prefix = "/communications/calls/";
            var path = resource.GetString()!;
            if (!path.StartsWith(prefix, StringComparison.Ordinal)) return false;
            var id = path[prefix.Length..];
            if (id.Length is < 1 or > 128 || id.Any(c => !char.IsAsciiLetterOrDigit(c) && c != '-')) return false;
            var next = state.GetString()!;
            if (next is not ("establishing" or "established" or "terminating" or "terminated")) return false;
            updates.Add((id, next));
        }
        lock (gate)
        {
            // A callback racing join registration must be retried, never silently discarded.
            if (updates.Any(update => !calls.ContainsKey(update.Id))) return false;
            var snapshot = new Dictionary<string, CallState>(calls, StringComparer.Ordinal);
            foreach (var update in updates)
                if (Rank(update.State) >= Rank(snapshot[update.Id].State))
                    snapshot[update.Id] = snapshot[update.Id] with { State = update.State, UpdatedAt = DateTimeOffset.UtcNow };
            Persist(snapshot);
            calls = snapshot;
            return true;
        }
    }
    private static int Rank(string state) => state switch { "establishing" => 0, "established" => 1, "terminating" => 2, _ => 3 };

    private void Load()
    {
        if (!File.Exists(stateFilePath)) return;
        var restored = JsonSerializer.Deserialize<Dictionary<string, CallState>>(File.ReadAllText(stateFilePath));
        if (restored is null || restored.Count > 1000) throw new InvalidDataException("Invalid Teams call state file.");
        foreach (var item in restored)
            if (item.Key.Length is > 0 and <= 128 && item.Value.State is "establishing" or "established" or "terminating" or "terminated")
                calls[item.Key] = item.Value;
    }

    private void Persist(Dictionary<string, CallState> snapshot)
    {
        if (string.IsNullOrWhiteSpace(stateFilePath)) return;
        var directory = Path.GetDirectoryName(stateFilePath)!;
        Directory.CreateDirectory(directory);
        var temporary = stateFilePath + ".tmp";
        File.WriteAllText(temporary, JsonSerializer.Serialize(snapshot));
        File.Move(temporary, stateFilePath, true);
    }

    private sealed record CallState(Guid MeetingId, string State, DateTimeOffset UpdatedAt);
}
