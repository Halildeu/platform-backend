using System.Globalization;
using System.Net.Http.Headers;
using System.Text.Json;
using Microsoft.Extensions.Options;

namespace TeamsCapture.Worker;

public sealed record TeamsCalendarEvent(DateTimeOffset StartsAt, DateTimeOffset EndsAt, bool Cancelled, string? JoinUrl,
    bool Missing = false);

public interface ITeamsCalendarClient
{
    Task<TeamsCalendarEvent?> ReadAsync(Guid organizerId, string eventId, CancellationToken cancellationToken);
    Task<ScheduledTeamsMeeting?> ResolveMeetingAsync(Guid organizerId, string joinUrl, CancellationToken cancellationToken);
}

/// <summary>Reads selected events or a bounded selection window in operator-allowed organizer mailboxes.</summary>
public sealed class GraphTeamsCalendarClient(HttpClient client, ITeamsAccessTokenProvider tokens,
    IOptions<TeamsCaptureOptions> settings) : ITeamsCalendarClient, ITeamsCalendarBrowser
{
    public async Task<CalendarEventChoices?> BrowseAsync(Guid organizerId, DateTimeOffset from, DateTimeOffset to,
        CancellationToken cancellationToken)
    {
        if (!Allowed(organizerId) || to <= from || to - from > TimeSpan.FromDays(31)) return null;
        var path = $"users/{organizerId:D}/calendar/calendarView";
        var relativeUrl = path + "?startDateTime=" + Uri.EscapeDataString(from.UtcDateTime.ToString("O", CultureInfo.InvariantCulture))
            + "&endDateTime=" + Uri.EscapeDataString(to.UtcDateTime.ToString("O", CultureInfo.InvariantCulture))
            + "&$top=100&$select=id,subject,isCancelled,isOnlineMeeting,isOrganizer,onlineMeetingProvider,start,end,type";
        var items = new List<CalendarEventChoice>();
        var ids = new HashSet<string>(StringComparer.Ordinal);
        var pages = new HashSet<string>(StringComparer.Ordinal);
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        deadline.CancelAfter(TimeSpan.FromSeconds(20));
        try
        {
            for (var page = 0; page < 5; page++)
            {
                if (!pages.Add(relativeUrl)) return null;
                var response = await GetAsync(relativeUrl, deadline.Token);
                using var document = response.Document;
                if (document is null || document.RootElement.ValueKind != JsonValueKind.Object
                    || !document.RootElement.TryGetProperty("value", out var values)
                    || values.ValueKind != JsonValueKind.Array || values.GetArrayLength() > 100) return null;
                foreach (var item in values.EnumerateArray())
                {
                    if (item.ValueKind != JsonValueKind.Object) return null;
                    // Attendee copies and non-Teams appointments cannot be scheduled by this organizer.
                    if (!Bool(item, "isCancelled", out var cancelled) || !Bool(item, "isOnlineMeeting", out var online)
                        || !Bool(item, "isOrganizer", out var owns)) return null;
                    if (cancelled || !online || !owns || Text(item, "onlineMeetingProvider") != "teamsForBusiness") continue;
                    var id = Text(item, "id");
                    var title = Text(item, "subject");
                    if (!CalendarSelection.ValidEventId(id!) || !ids.Add(id!) || title is null || title.Length > 1024
                        || Text(item, "type") is not ("singleInstance" or "occurrence" or "exception")
                        || !UtcTime(item, "start", out var start) || !UtcTime(item, "end", out var end) || end <= start) return null;
                    if (start >= from && start < to) items.Add(new(id!, title, start, end));
                }
                if (!document.RootElement.TryGetProperty("@odata.nextLink", out var next))
                    return new(items.OrderBy(i => i.StartsAt).ThenBy(i => i.EventId, StringComparer.Ordinal).ToArray(), false);
                if (next.ValueKind != JsonValueKind.String || !SafeNextPage(next.GetString(), path, out relativeUrl)) return null;
                if (page == 4)
                    return new(items.OrderBy(i => i.StartsAt).ThenBy(i => i.EventId, StringComparer.Ordinal).ToArray(), true);
            }
            return null;
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested) { return null; }
    }

    private static bool Bool(JsonElement value, string name, out bool result)
    {
        result = false;
        if (!value.TryGetProperty(name, out var property) || property.ValueKind is not (JsonValueKind.True or JsonValueKind.False)) return false;
        result = property.GetBoolean();
        return true;
    }

    private static bool SafeNextPage(string? url, string path, out string relative)
    {
        relative = "";
        if (url is not { Length: > 0 and <= 16384 } || url.Any(char.IsControl)
            || !Uri.TryCreate(url, UriKind.Absolute, out var uri) || uri.Scheme != "https"
            || uri.Host != "graph.microsoft.com" || uri.Port != 443 || uri.UserInfo.Length != 0 || uri.Fragment.Length != 0
            || uri.AbsolutePath != "/v1.0/" + path || uri.Query.Length == 0) return false;
        relative = path + uri.Query;
        return true;
    }

    public async Task<TeamsCalendarEvent?> ReadAsync(Guid organizerId, string eventId, CancellationToken cancellationToken)
    {
        if (!Allowed(organizerId) || !CalendarSelection.ValidEventId(eventId)) return null;
        var response = await GetAsync($"users/{organizerId:D}/events/{Uri.EscapeDataString(eventId)}"
            + "?$select=id,isCancelled,isOnlineMeeting,onlineMeetingProvider,onlineMeeting,start,end,type", cancellationToken);
        using var document = response.Document;
        if (response.Status == System.Net.HttpStatusCode.NotFound)
            return new(DateTimeOffset.MinValue, DateTimeOffset.MinValue, false, null, Missing: true);
        if (document is null) return null;
        var root = document.RootElement;
        if (Text(root, "id") != eventId || Text(root, "type") is not ("singleInstance" or "occurrence" or "exception")) return null;
        if (root.TryGetProperty("isCancelled", out var cancelled) && cancelled.ValueKind == JsonValueKind.True)
            return new(DateTimeOffset.MinValue, DateTimeOffset.MinValue, true, null);
        if (cancelled.ValueKind != JsonValueKind.False || !root.TryGetProperty("isOnlineMeeting", out var online)
            || online.ValueKind != JsonValueKind.True || Text(root, "onlineMeetingProvider") != "teamsForBusiness"
            || !UtcTime(root, "start", out var start) || !UtcTime(root, "end", out var end) || end <= start
            || !root.TryGetProperty("onlineMeeting", out var meeting)) return null;
        var url = Text(meeting, "joinUrl");
        return ValidJoinUrl(url) ? new(start, end, false, url) : null;
    }

    public async Task<ScheduledTeamsMeeting?> ResolveMeetingAsync(Guid organizerId, string joinUrl, CancellationToken cancellationToken)
    {
        if (!Allowed(organizerId) || !ValidJoinUrl(joinUrl)) return null;
        // Join URLs are opaque: resolve through Graph instead of parsing legacy URL paths/context.
        var filter = Uri.EscapeDataString("JoinWebUrl eq '" + joinUrl.Replace("'", "''", StringComparison.Ordinal) + "'");
        var response = await GetAsync($"users/{organizerId:D}/onlineMeetings?$filter={filter}", cancellationToken);
        using var document = response.Document;
        if (document is null) return null;
        var root = document.RootElement;
        if (root.TryGetProperty("@odata.nextLink", out _) || !root.TryGetProperty("value", out var values)
            || values.ValueKind != JsonValueKind.Array || values.GetArrayLength() != 1) return null;
        var item = values[0];
        if (Text(item, "joinWebUrl") != joinUrl || !Object(item, "chatInfo", out var chat)
            || !Object(item, "participants", out var participants)
            || !Object(participants, "organizer", out var organizer)
            || !Object(organizer, "identity", out var identity)
            || !Object(identity, "user", out var user)
            || !Guid.TryParse(Text(user, "id"), out var actualOrganizer) || actualOrganizer != organizerId) return null;
        var thread = Text(chat, "threadId");
        var message = Text(chat, "messageId");
        if (string.IsNullOrWhiteSpace(thread) || thread.Any(char.IsControl)
            || string.IsNullOrWhiteSpace(message) || message.Any(char.IsControl)) return null;
        var result = new ScheduledTeamsMeeting(thread, message, organizerId.ToString("D"));
        return result.IsValid() ? result : null;
    }

    private bool Allowed(Guid organizer) => settings.Value.IsReadyForCalendarScheduling()
        && settings.Value.CalendarOrganizerIds.Contains(organizer);

    private async Task<(System.Net.HttpStatusCode Status, JsonDocument? Document)> GetAsync(string relativeUrl, CancellationToken cancellationToken)
    {
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        deadline.CancelAfter(TimeSpan.FromSeconds(15));
        try
        {
            var token = await tokens.GetAccessTokenAsync(deadline.Token);
            if (string.IsNullOrWhiteSpace(token)) return (default, null);
            using var request = new HttpRequestMessage(HttpMethod.Get, "https://graph.microsoft.com/v1.0/" + relativeUrl);
            request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);
            request.Headers.TryAddWithoutValidation("Prefer", "outlook.timezone=\"UTC\"");
            using var response = await client.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, deadline.Token);
            if (response.StatusCode != System.Net.HttpStatusCode.OK) return (response.StatusCode, null);
            await response.Content.LoadIntoBufferAsync(1024 * 1024).WaitAsync(deadline.Token);
            var parsed = JsonDocument.Parse(await response.Content.ReadAsStringAsync(deadline.Token));
            if (parsed.RootElement.ValueKind == JsonValueKind.Object) return (response.StatusCode, parsed);
            parsed.Dispose();
            return (default, null);
        }
        catch (Exception error) when (error is JsonException or HttpRequestException or IOException) { return (default, null); }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested) { return (default, null); }
    }

    private static string? Text(JsonElement element, string name) => element.ValueKind == JsonValueKind.Object
        && element.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.String ? value.GetString() : null;

    private static bool Object(JsonElement parent, string name, out JsonElement value)
    {
        value = default;
        return parent.ValueKind == JsonValueKind.Object && parent.TryGetProperty(name, out value)
            && value.ValueKind == JsonValueKind.Object;
    }

    private static bool ValidJoinUrl(string? value) => value is { Length: > 0 and <= 8192 }
        && !value.Any(char.IsControl) && Uri.TryCreate(value, UriKind.Absolute, out var uri)
        && uri.Scheme == "https" && string.IsNullOrEmpty(uri.UserInfo);

    private static bool UtcTime(JsonElement root, string name, out DateTimeOffset time)
    {
        time = default;
        return root.TryGetProperty(name, out var value) && Text(value, "timeZone") == "UTC"
            && DateTimeOffset.TryParseExact(Text(value, "dateTime"),
                ["yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'HH:mm:ss.FFFFFFF", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ss.FFFFFFF'Z'"],
                CultureInfo.InvariantCulture, DateTimeStyles.AssumeUniversal | DateTimeStyles.AdjustToUniversal, out time);
    }
}
