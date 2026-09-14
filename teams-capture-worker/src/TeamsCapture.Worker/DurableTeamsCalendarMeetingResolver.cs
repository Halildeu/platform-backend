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
    private readonly Dictionary<string, CalendarReference> meetings = new(StringComparer.Ordinal);
    private readonly string? stateFilePath;

    public DurableTeamsCalendarMeetingResolver(IOptions<TeamsCaptureOptions> options)
    {
        stateFilePath = options.Value.CalendarStateFilePath;
        if (!string.IsNullOrWhiteSpace(stateFilePath) && File.Exists(stateFilePath))
        {
            var restored = JsonSerializer.Deserialize<Dictionary<string, CalendarReference>>(
                File.ReadAllText(stateFilePath));
            if (restored is null || restored.Count > 1000) throw new InvalidDataException("Invalid Teams calendar state file.");
            foreach (var item in restored)
                if (ValidReference(item.Key) && item.Value.MeetingId != Guid.Empty && item.Value.Meeting.IsValid())
                    meetings[item.Key] = item.Value;
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
            meetings.Add(calendarEventId, proposed);
            Persist();
            return true;
        }
    }

    private static bool ValidReference(string value) => value is { Length: > 0 and <= 256 }
        && value.All(character => char.IsAsciiLetterOrDigit(character) || character is '-' or '_' or ':' or '.');

    private void Persist()
    {
        if (string.IsNullOrWhiteSpace(stateFilePath)) return;
        var directory = Path.GetDirectoryName(stateFilePath)!;
        Directory.CreateDirectory(directory);
        var temporary = stateFilePath + ".tmp";
        File.WriteAllText(temporary, JsonSerializer.Serialize(meetings));
        File.Move(temporary, stateFilePath, true);
    }

    private sealed record CalendarReference(Guid MeetingId, ScheduledTeamsMeeting Meeting);
}
