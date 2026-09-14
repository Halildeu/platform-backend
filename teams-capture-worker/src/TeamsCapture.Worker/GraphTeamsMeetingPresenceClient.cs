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
    private readonly TeamsCallbackState callbackState;

    public GraphTeamsMeetingPresenceClient(
        IOptions<TeamsCaptureOptions> options,
        ITeamsCalendarMeetingResolver calendarResolver,
        ITeamsAccessTokenProvider tokenProvider,
        HttpClient httpClient,
        TeamsCallbackState callbackState)
    {
        this.options = options.Value;
        this.calendarResolver = calendarResolver;
        this.tokenProvider = tokenProvider;
        this.httpClient = httpClient;
        this.callbackState = callbackState;
    }

    public async Task<TeamsJoinReceipt?> JoinAsync(
        MeetingPresenceCommand command,
        CancellationToken cancellationToken)
    {
        if (!options.IsReadyForRegistration())
        {
            return null;
        }

        var meeting = await calendarResolver
            .ResolveAsync(command.CalendarEventId, cancellationToken)
            .ConfigureAwait(false);
        if (meeting is null || !meeting.IsValid())
        {
            return null;
        }

        var accessToken = await tokenProvider.GetAccessTokenAsync(cancellationToken).ConfigureAwait(false);
        if (string.IsNullOrWhiteSpace(accessToken))
        {
            return null;
        }

        using var request = new HttpRequestMessage(HttpMethod.Post, "https://graph.microsoft.com/v1.0/communications/calls");
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", accessToken);
        request.Headers.TryAddWithoutValidation("X-Correlation-Id", command.CorrelationId);
        request.Content = new StringContent(
            JsonSerializer.Serialize(BuildJoinRequest(meeting)),
            Encoding.UTF8,
            "application/json");

        using var response = await httpClient.SendAsync(request, cancellationToken).ConfigureAwait(false);
        if (!response.IsSuccessStatusCode)
        {
            return null;
        }

        await using var stream = await response.Content.ReadAsStreamAsync(cancellationToken).ConfigureAwait(false);
        using var body = await JsonDocument.ParseAsync(stream, cancellationToken: cancellationToken).ConfigureAwait(false);
        return body.RootElement.TryGetProperty("id", out var id) && id.ValueKind == JsonValueKind.String
               && !string.IsNullOrWhiteSpace(id.GetString())
            && callbackState.Register(id.GetString()!, command.MeetingId) ? new TeamsJoinReceipt(id.GetString()!)
            : null;
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
