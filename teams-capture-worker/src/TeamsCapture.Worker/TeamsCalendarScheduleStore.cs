using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using Microsoft.Extensions.Options;

namespace TeamsCapture.Worker;

public sealed record CalendarSelection(Guid MeetingId, Guid OrganizerId, string EventId, string CorrelationId, TeamsScheduleActor? Actor = null)
{
    public string Reference => "outlook-" + Convert.ToHexString(SHA256.HashData(
        Encoding.UTF8.GetBytes(OrganizerId.ToString("D") + "\0" + EventId))).ToLowerInvariant();
    public bool IsValid() => MeetingId != Guid.Empty && OrganizerId != Guid.Empty && ValidEventId(EventId)
        && new MeetingPresenceCommand(MeetingId, Reference, CorrelationId).IsValid() && (Actor is null || Actor.IsValid());
    public static bool ValidEventId(string? id) => id is { Length: > 0 and <= 2048 }
        && id.All(c => char.IsAsciiLetterOrDigit(c) || c is '-' or '_' or '+' or '/' or '=');
}

public sealed record CalendarSchedule(CalendarSelection Selection, string State, DateTimeOffset StartsAt,
    DateTimeOffset EndsAt, DateTimeOffset NextCheckAt, DateTimeOffset UpdatedAt, string? CallId = null, string? Failure = null)
{
    public bool Terminal => State is "joined" or "cancelled" or "expired" or "failed";
}

/// <summary>One process/replica, bounded durable queue; only explicit selections, never mailbox scanning.</summary>
public sealed class TeamsCalendarScheduleStore
{
    private readonly object gate = new();
    private readonly DurableTeamsSnapshot? storage;
    private Dictionary<Guid, CalendarSchedule> schedules = new();

    public TeamsCalendarScheduleStore(IOptions<TeamsCaptureOptions> settings)
    {
        if (!settings.Value.IsReadyForCalendarScheduling()) return;
        storage = new(settings.Value.CalendarScheduleStateFilePath!, "calendar-schedules");
        var payload = storage.Read();
        if (payload is null) return;
        try
        {
            var restored = JsonSerializer.Deserialize<Dictionary<Guid, CalendarSchedule>>(payload);
            if (restored is null || restored.Count > 100 || restored.Any(p => p.Value is null
                || p.Value.Selection is null || p.Key != p.Value.Selection.MeetingId || !p.Value.Selection.IsValid()
                || p.Value.State is not ("pending" or "dispatching" or "joined" or "cancelled" or "expired" or "failed")
                || p.Value.EndsAt <= p.Value.StartsAt
                || p.Value.CallId is not null && !TeamsCallbackState.ValidCallId(p.Value.CallId))
                || restored.Values.Select(x => x.Selection.Reference).Distinct().Count() != restored.Count)
                throw new InvalidDataException("Invalid Teams calendar schedule state.");
            schedules = restored;
        }
        catch (JsonException error) { throw new InvalidDataException("Invalid Teams calendar schedule state.", error); }
    }

    public CalendarSchedule? Read(Guid id) { lock (gate) return schedules.GetValueOrDefault(id); }
    public CalendarSchedule[] Due(DateTimeOffset now)
    { lock (gate) return schedules.Values.Where(x => !x.Terminal && x.NextCheckAt <= now).ToArray(); }

    public bool Add(CalendarSchedule proposed)
    {
        if (!proposed.Selection.IsValid() || proposed.Selection.Actor is null || proposed.State != "pending" || proposed.EndsAt <= proposed.StartsAt) return false;
        lock (gate)
        {
            if (schedules.TryGetValue(proposed.Selection.MeetingId, out var old)) return old.Selection == proposed.Selection;
            if (schedules.Count >= 100 || schedules.Values.Any(x => x.Selection.Reference == proposed.Selection.Reference)) return false;
            var next = new Dictionary<Guid, CalendarSchedule>(schedules) { [proposed.Selection.MeetingId] = proposed };
            Save(next);
            return true;
        }
    }

    public bool Replace(CalendarSchedule expected, CalendarSchedule replacement)
    {
        lock (gate)
        {
            if (replacement.Selection != expected.Selection || !schedules.TryGetValue(expected.Selection.MeetingId, out var old)
                || old != expected) return false;
            Save(new(schedules) { [expected.Selection.MeetingId] = replacement });
            return true;
        }
    }

    public void RemoveTerminalBefore(DateTimeOffset cutoff)
    {
        lock (gate)
        {
            var next = schedules.Where(x => !x.Value.Terminal || x.Value.UpdatedAt >= cutoff).ToDictionary();
            if (next.Count != schedules.Count) Save(next);
        }
    }

    private void Save(Dictionary<Guid, CalendarSchedule> next)
    {
        if (storage is null) throw new IOException("Teams calendar scheduling storage is unavailable.");
        storage.Write(JsonSerializer.Serialize(next));
        schedules = next;
    }
}
