using System.Text.Json;
using Microsoft.Extensions.Options;

namespace TeamsCapture.Worker;

/// <summary>
/// Resolves opaque calendar references supplied by the trusted platform control plane.
/// This avoids requesting tenant-wide calendar permissions from Microsoft Graph.
/// </summary>
public sealed class DurableTeamsCalendarMeetingResolver : ITeamsCalendarMeetingResolver
{
    private readonly object gate = new();
    private Dictionary<string, CalendarReference> meetings = new(StringComparer.Ordinal);
    private readonly DurableTeamsSnapshot? store;

    public DurableTeamsCalendarMeetingResolver(IOptions<TeamsCaptureOptions> options)
    {
        if (!string.IsNullOrWhiteSpace(options.Value.CalendarStateFilePath))
        {
            store = new(options.Value.CalendarStateFilePath, "calendar");
            var payload = store.Read();
            if (payload is null) return;
            var restored = JsonSerializer.Deserialize<Dictionary<string, CalendarReference>>(payload);
            if (restored is null || restored.Count > 1000) throw new InvalidDataException("Invalid Teams calendar state file.");
            foreach (var item in restored)
            {
                if (!ValidReference(item.Key) || item.Value is null || item.Value.MeetingId == Guid.Empty
                    || item.Value.Meeting is null || !item.Value.Meeting.IsValid())
                    throw new InvalidDataException("Invalid Teams calendar state file.");
                meetings[item.Key] = item.Value;
            }
        }
    }

    public Task<ScheduledTeamsMeeting?> ResolveAsync(string calendarEventId, CancellationToken cancellationToken)
    {
        cancellationToken.ThrowIfCancellationRequested();
        lock (gate) return Task.FromResult(meetings.GetValueOrDefault(calendarEventId)?.Meeting);
    }

    public bool Register(Guid meetingId, string calendarEventId, ScheduledTeamsMeeting meeting)
    {
        if (meetingId == Guid.Empty || !ValidReference(calendarEventId) || !meeting.IsValid()) return false;
        var proposed = new CalendarReference(meetingId, meeting);
        lock (gate)
        {
            if (meetings.TryGetValue(calendarEventId, out var existing)) return existing == proposed;
            if (meetings.Count >= 1000) return false;
            var snapshot = new Dictionary<string, CalendarReference>(meetings, StringComparer.Ordinal);
            snapshot.Add(calendarEventId, proposed);
            Persist(snapshot);
            meetings = snapshot;
            return true;
        }
    }

    public void RemoveCompleted(IEnumerable<Guid> meetingIds)
    {
        var completed = meetingIds.ToHashSet();
        lock (gate)
        {
            if (!meetings.Values.Any(value => completed.Contains(value.MeetingId))) return;
            var snapshot = meetings.Where(item => !completed.Contains(item.Value.MeetingId))
                .ToDictionary(item => item.Key, item => item.Value, StringComparer.Ordinal);
            Persist(snapshot);
            meetings = snapshot;
        }
    }

    public static bool ValidReference(string? value) => value is { Length: > 0 and <= 256 }
        && value.All(character => char.IsAsciiLetterOrDigit(character) || character is '-' or '_' or ':' or '.');

    private void Persist(Dictionary<string, CalendarReference> snapshot)
    {
        store?.Write(JsonSerializer.Serialize(snapshot));
    }

    private sealed record CalendarReference(Guid MeetingId, ScheduledTeamsMeeting Meeting);
}
