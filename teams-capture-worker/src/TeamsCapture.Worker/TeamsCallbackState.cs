using System.Text.Json;

namespace TeamsCapture.Worker;

/// <summary>Bounded, process-local call status; not durable meeting/analysis storage.</summary>
public sealed class TeamsCallbackState
{
    private readonly object gate = new();
    private readonly Dictionary<string, string> calls = new(StringComparer.Ordinal);
    public bool Register(string id)
    {
        lock (gate)
        {
            if (calls.ContainsKey(id)) return true;
            if (calls.Count >= 1000) return false;
            calls.Add(id, "establishing");
            return true;
        }
    }

    public string? Read(string id) { lock (gate) return calls.GetValueOrDefault(id); }

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
            foreach (var update in updates)
                if (Rank(update.State) >= Rank(calls[update.Id])) calls[update.Id] = update.State;
            return true;
        }
    }
    private static int Rank(string state) => state switch { "establishing" => 0, "established" => 1, "terminating" => 2, _ => 3 };
}
