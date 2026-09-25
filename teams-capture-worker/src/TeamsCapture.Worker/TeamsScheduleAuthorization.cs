using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text;
using System.Text.Json;
using Microsoft.Extensions.Options;

namespace TeamsCapture.Worker;

// Identity only: populated by meeting-service after user authorization, never by the browser.
public sealed record TeamsScheduleActor(int Version, string Issuer, string Subject, Guid OrganizationId,
    Guid MicrosoftTenantId, string AuthzPrincipal, long UserId, long CompanyId)
{
    public bool IsValid() => Version == 1 && OrganizationId != Guid.Empty && MicrosoftTenantId != Guid.Empty
        && UserId > 0 && CompanyId > 0 && Subject is { Length: > 0 and <= 200 } && !string.IsNullOrWhiteSpace(Subject)
        && !Subject.Any(char.IsControl) && (AuthzPrincipal == Subject || AuthzPrincipal == UserId.ToString(System.Globalization.CultureInfo.InvariantCulture))
        && Issuer is { Length: > 0 and <= 2048 } && Uri.TryCreate(Issuer, UriKind.Absolute, out var uri)
        && uri.Scheme == "https" && !string.IsNullOrEmpty(uri.Host) && string.IsNullOrEmpty(uri.UserInfo)
        && string.IsNullOrEmpty(uri.Query) && string.IsNullOrEmpty(uri.Fragment);
}

public sealed class TeamsScheduleAuthorizationOptions
{
    public bool Enabled { get; init; }
    public string AuthServiceBaseUrl { get; init; } = "http://auth-service:8088";
    public string MeetingServiceBaseUrl { get; init; } = "http://meeting-service:8097";
    public string? ClientSecret { get; init; }
    public bool IsConfigured() => Enabled && ClientSecret is { Length: >= 32 and <= 4096 }
        && Origin(AuthServiceBaseUrl) && Origin(MeetingServiceBaseUrl);
    private static bool Origin(string value) => Uri.TryCreate(value, UriKind.Absolute, out var uri)
        && uri.Scheme is "http" or "https" && uri.AbsolutePath == "/" && !string.IsNullOrEmpty(uri.Host)
        && string.IsNullOrEmpty(uri.UserInfo) && string.IsNullOrEmpty(uri.Query) && string.IsNullOrEmpty(uri.Fragment);
}

public enum ScheduleAuthorization { Allowed, Denied, Unavailable }
public interface ITeamsScheduleAuthorizer
{
    bool IsConfigured { get; }
    Task<ScheduleAuthorization> AuthorizeAsync(CalendarSelection selection, CancellationToken token);
}

// Separate SERVICE identity and audience. A user's expiring bearer is never persisted or forwarded.
public sealed class HttpTeamsScheduleAuthorizer(IOptions<TeamsScheduleAuthorizationOptions> settings, HttpClient http)
    : ITeamsScheduleAuthorizer
{
    public bool IsConfigured => settings.Value.IsConfigured();
    public async Task<ScheduleAuthorization> AuthorizeAsync(CalendarSelection selection, CancellationToken token)
    {
        if (!IsConfigured || selection.Actor?.IsValid() != true) return ScheduleAuthorization.Unavailable;
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(token);
        deadline.CancelAfter(TimeSpan.FromSeconds(25));
        try
        {
            var config = settings.Value;
            using var mint = new HttpRequestMessage(HttpMethod.Post, config.AuthServiceBaseUrl.TrimEnd('/') + "/oauth2/token");
            mint.Headers.Authorization = new AuthenticationHeaderValue("Basic", Convert.ToBase64String(
                Encoding.UTF8.GetBytes("teams-capture-worker:" + config.ClientSecret)));
            mint.Content = new FormUrlEncodedContent(new Dictionary<string, string> {
                ["grant_type"] = "client_credentials", ["audience"] = "meeting-service",
                ["permissions"] = "meeting:teams-schedule:authorize" });
            using var minted = await http.SendAsync(mint, HttpCompletionOption.ResponseHeadersRead, deadline.Token);
            if (minted.StatusCode != HttpStatusCode.OK) return ScheduleAuthorization.Unavailable;
            await minted.Content.LoadIntoBufferAsync(64 * 1024).WaitAsync(deadline.Token);
            using var body = JsonDocument.Parse(await minted.Content.ReadAsStringAsync(deadline.Token));
            var root = body.RootElement;
            if (root.ValueKind != JsonValueKind.Object || !root.TryGetProperty("access_token", out var access)
                || access.ValueKind != JsonValueKind.String || access.GetString() is not { Length: > 0 and <= 16384 } bearer
                || bearer.Any(char.IsWhiteSpace) || bearer.Any(char.IsControl)
                || !root.TryGetProperty("token_type", out var type) || type.ValueKind != JsonValueKind.String
                || !string.Equals(type.GetString(), "Bearer", StringComparison.OrdinalIgnoreCase)
                || !root.TryGetProperty("expires_in", out var expiry) || !expiry.TryGetInt32(out var ttl) || ttl < 30)
                return ScheduleAuthorization.Unavailable;
            using var request = new HttpRequestMessage(HttpMethod.Post, config.MeetingServiceBaseUrl.TrimEnd('/')
                + $"/api/v1/internal/meetings/{selection.MeetingId:D}/teams-calendar/authorize");
            request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", bearer);
            request.Content = JsonContent.Create(new { selection.OrganizerId, selection.Actor });
            using var response = await http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, deadline.Token);
            return response.StatusCode switch {
                HttpStatusCode.NoContent => ScheduleAuthorization.Allowed,
                HttpStatusCode.BadRequest or HttpStatusCode.Forbidden or HttpStatusCode.NotFound or HttpStatusCode.Conflict => ScheduleAuthorization.Denied,
                _ => ScheduleAuthorization.Unavailable
            };
        }
        catch (Exception error) when (error is HttpRequestException or OperationCanceledException or JsonException or InvalidOperationException)
        { return ScheduleAuthorization.Unavailable; }
    }
}

public interface ITeamsScheduleDispatchGuard
{
    Task ValidateAsync(MeetingPresenceCommand command, ScheduledTeamsMeeting meeting, CancellationToken token);
}

public sealed class TeamsScheduleDispatchGuard(TeamsCalendarScheduleStore store, ITeamsScheduleAuthorizer authorizer,
    IOptions<TeamsCaptureOptions> settings, TimeProvider clock) : ITeamsScheduleDispatchGuard
{
    public async Task ValidateAsync(MeetingPresenceCommand command, ScheduledTeamsMeeting meeting, CancellationToken token)
    {
        var item = store.Read(command.MeetingId);
        // Private operator commands retain their existing admission; selected Outlook commands always require a stored grant.
        if (item is null && !command.CalendarEventId.StartsWith("outlook-", StringComparison.Ordinal)) return;
        if (item is null || item.State != "dispatching" || item.Selection.Reference != command.CalendarEventId
            || item.Selection.CorrelationId != command.CorrelationId || item.Selection.Actor?.IsValid() != true
            || !Guid.TryParse(meeting.OrganizerUserId, out var organizer) || organizer != item.Selection.OrganizerId
            || !Guid.TryParse(settings.Value.TenantId, out var tenant) || tenant != item.Selection.Actor.MicrosoftTenantId
            || !settings.Value.CalendarOrganizerIds.Contains(organizer))
            throw new TeamsScheduleNotAuthorizedException("schedule_authorization_denied");
        var result = await authorizer.AuthorizeAsync(item.Selection, token);
        if (result != ScheduleAuthorization.Allowed) throw new TeamsScheduleNotAuthorizedException(
            result == ScheduleAuthorization.Denied ? "schedule_authorization_denied" : "schedule_authorization_unavailable");
        token.ThrowIfCancellationRequested();
        var now = clock.GetUtcNow();
        if (store.Read(command.MeetingId) != item) throw new TeamsScheduleNotAuthorizedException("schedule_authorization_denied");
        if (item.StartsAt > now || item.EndsAt <= now || now - item.StartsAt > TimeSpan.FromMinutes(5))
            throw new TeamsScheduleNotAuthorizedException("schedule_join_window_elapsed");
    }
}

// Only thrown before Graph SendAsync; the coordinator can safely release its unsent reservation.
public sealed class TeamsScheduleNotAuthorizedException(string code) : Exception { public string Code { get; } = code; }
