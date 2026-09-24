using System.Net.Http.Headers;
using System.Text;
using System.Text.Json;
using Microsoft.Extensions.Options;

namespace TeamsCapture.Worker;

/// <summary>
/// Microsoft Graph calling client for visible, service-hosted bot presence.
/// It never requests app-hosted media and never receives or persists audio.
/// </summary>
public sealed class GraphTeamsMeetingPresenceClient : ITeamsMeetingPresenceClient
{
    private readonly TeamsCaptureOptions options;
    private readonly ITeamsCalendarMeetingResolver calendarResolver;
    private readonly ITeamsAccessTokenProvider tokenProvider;
    private readonly HttpClient httpClient;

    public GraphTeamsMeetingPresenceClient(
        IOptions<TeamsCaptureOptions> options,
        ITeamsCalendarMeetingResolver calendarResolver,
        ITeamsAccessTokenProvider tokenProvider,
        HttpClient httpClient)
    {
        this.options = options.Value;
        this.calendarResolver = calendarResolver;
        this.tokenProvider = tokenProvider;
        this.httpClient = httpClient;
    }

    public async Task<TeamsJoinReceipt?> JoinAsync(
        MeetingPresenceCommand command,
        CancellationToken cancellationToken)
    {
        if (!options.IsReadyForRegistration()) throw new TeamsJoinNotCreatedException();
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        deadline.CancelAfter(TimeSpan.FromSeconds(30));
        ScheduledTeamsMeeting? meeting;
        string? accessToken;
        try
        {
            deadline.Token.ThrowIfCancellationRequested();
            meeting = await calendarResolver.ResolveAsync(command.CalendarEventId, deadline.Token).ConfigureAwait(false);
            if (meeting is null || !meeting.IsValid()) throw new TeamsJoinNotCreatedException();
            accessToken = await tokenProvider.GetAccessTokenAsync(deadline.Token).ConfigureAwait(false);
            if (string.IsNullOrWhiteSpace(accessToken)) throw new TeamsJoinNotCreatedException();
            deadline.Token.ThrowIfCancellationRequested();
        }
        catch (Exception error) when (error is HttpRequestException or OperationCanceledException)
        {
            // Token acquisition can fail without having sent a create-call request.
            throw new TeamsJoinNotCreatedException();
        }

        using var request = new HttpRequestMessage(HttpMethod.Post, "https://graph.microsoft.com/v1.0/communications/calls");
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", accessToken);
        request.Headers.TryAddWithoutValidation("X-Correlation-Id", command.CorrelationId);
        request.Content = new StringContent(
            JsonSerializer.Serialize(BuildJoinRequest(meeting)),
            Encoding.UTF8,
            "application/json");

        using var response = await httpClient.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, deadline.Token).ConfigureAwait(false);
        // These responses explicitly reject creation. 408, 5xx, redirects and malformed
        // success responses stay ambiguous: a caller must not blindly create another call.
        if (response.StatusCode is System.Net.HttpStatusCode.BadRequest or System.Net.HttpStatusCode.Unauthorized
            or System.Net.HttpStatusCode.Forbidden or System.Net.HttpStatusCode.NotFound
            or System.Net.HttpStatusCode.TooManyRequests) throw new TeamsJoinNotCreatedException();
        if (response.StatusCode != System.Net.HttpStatusCode.Created)
        {
            return null;
        }

        try
        {
            await response.Content.LoadIntoBufferAsync(1024 * 1024).WaitAsync(deadline.Token).ConfigureAwait(false);
            using var body = JsonDocument.Parse(await response.Content.ReadAsStringAsync(deadline.Token).ConfigureAwait(false));
            return body.RootElement.ValueKind == JsonValueKind.Object
                && body.RootElement.TryGetProperty("id", out var id) && id.ValueKind == JsonValueKind.String
                && TeamsCallbackState.ValidCallId(id.GetString()) ? new TeamsJoinReceipt(id.GetString()!) : null;
        }
        catch (Exception error) when (error is JsonException or HttpRequestException) { return null; }
    }

    private Dictionary<string, object?> BuildJoinRequest(ScheduledTeamsMeeting meeting) => new()
    {
        ["@odata.type"] = "#microsoft.graph.call",
        ["callbackUri"] = options.PublicCallbackBaseUrl!.TrimEnd('/') + "/api/teams/callback",
        ["requestedModalities"] = new[] { "audio" },
        ["mediaConfig"] = new Dictionary<string, object?>
        {
            ["@odata.type"] = "#microsoft.graph.serviceHostedMediaConfig"
        },
        ["chatInfo"] = new Dictionary<string, object?>
        {
            ["@odata.type"] = "#microsoft.graph.chatInfo",
            ["threadId"] = meeting.ThreadId,
            ["messageId"] = meeting.MessageId
        },
        ["meetingInfo"] = new Dictionary<string, object?>
        {
            ["@odata.type"] = "#microsoft.graph.organizerMeetingInfo",
            ["organizer"] = new Dictionary<string, object?>
            {
                ["@odata.type"] = "#microsoft.graph.identitySet",
                ["user"] = new Dictionary<string, object?>
                {
                    ["@odata.type"] = "#microsoft.graph.identity",
                    ["id"] = meeting.OrganizerUserId,
                    ["tenantId"] = options.TenantId
                }
            },
            ["allowConversationWithoutHost"] = true
        },
        ["tenantId"] = options.TenantId
    };
}

public sealed record ScheduledTeamsMeeting(string ThreadId, string MessageId, string OrganizerUserId)
{
    public bool IsValid() => ThreadId is { Length: > 0 and <= 2048 }
        && MessageId is { Length: > 0 and <= 256 }
        && Guid.TryParse(OrganizerUserId, out _);
}

public interface ITeamsCalendarMeetingResolver
{
    Task<ScheduledTeamsMeeting?> ResolveAsync(string calendarEventId, CancellationToken cancellationToken);
}

public interface ITeamsAccessTokenProvider
{
    Task<string?> GetAccessTokenAsync(CancellationToken cancellationToken);
}
