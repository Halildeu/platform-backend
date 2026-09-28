using System.Net.Http.Headers;
using System.Text.Json;
using System.Text.Json.Serialization;
using Microsoft.Extensions.Options;

namespace TeamsCapture.Worker;

/// <summary>
/// Reads a current roster for an established, locally registered call. This is
/// source metadata, not evidence that a participant is speaking or owns a phrase.
/// Names and source IDs are returned to the internal caller, never persisted.
/// </summary>
public sealed class GraphTeamsParticipantRosterClient(
    HttpClient client,
    IOptions<TeamsCaptureOptions> settings,
    ITeamsAccessTokenProvider tokens,
    TeamsCallbackState calls) : ITeamsParticipantRosterClient
{
    private const int MaxResponseBytes = 1024 * 1024;
    private static readonly JsonSerializerOptions JsonOptions = new(JsonSerializerDefaults.Web);

    public async Task<TeamsParticipantRoster?> ReadAsync(string callId, CancellationToken cancellationToken)
    {
        if (!settings.Value.IsReadyForRegistration()
            || callId.Length is < 1 or > 128
            || callId.Any(c => !char.IsAsciiLetterOrDigit(c) && c != '-')
            || calls.Read(callId) != "established"
            || calls.ReadMeetingId(callId) is not { } meetingId) return null;

        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        deadline.CancelAfter(TimeSpan.FromSeconds(15));
        try
        {
            var token = await tokens.GetAccessTokenAsync(deadline.Token).ConfigureAwait(false);
            if (string.IsNullOrWhiteSpace(token)) return null;
            using var request = new HttpRequestMessage(HttpMethod.Get,
                $"https://graph.microsoft.com/v1.0/communications/calls/{callId}/participants");
            request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);
            using var response = await client.SendAsync(request, HttpCompletionOption.ResponseHeadersRead,
                deadline.Token).ConfigureAwait(false);
            if (!response.IsSuccessStatusCode || response.Content.Headers.ContentLength > MaxResponseBytes) return null;

            await using var stream = await response.Content.ReadAsStreamAsync(deadline.Token).ConfigureAwait(false);
            using var buffer = new MemoryStream();
            var chunk = new byte[8192];
            int count;
            while ((count = await stream.ReadAsync(chunk, deadline.Token).ConfigureAwait(false)) > 0)
            {
                if (buffer.Length + count > MaxResponseBytes) return null;
                buffer.Write(chunk, 0, count);
            }
            var roster = JsonSerializer.Deserialize<GraphRoster>(buffer.ToArray(), JsonOptions);
            // A partial page must never masquerade as the complete source mapping.
            // Paging is deliberately not followed (including arbitrary nextLink URLs).
            if (roster?.Value is null || roster.Value.Length > 1000 || !string.IsNullOrEmpty(roster.NextLink)) return null;
            var participants = new List<TeamsParticipant>();
            var participantIds = new HashSet<string>(StringComparer.Ordinal);
            foreach (var participant in roster.Value)
            {
                if (participant is null || !ValidText(participant.Id, 256)
                    || !participantIds.Add(participant.Id!) || participant.MediaStreams is null) return null;
                var user = participant.Info?.Identity?.User;
                if (user?.Id is not null && !ValidText(user.Id, 256)
                    || user?.DisplayName is not null && !ValidText(user.DisplayName, 256)) return null;
                var sources = new HashSet<string>(StringComparer.Ordinal);
                foreach (var media in participant.MediaStreams)
                {
                    if (media is null) return null;
                    if (media.MediaType != "audio") continue;
                    if (media.SourceId is null) continue;
                    if (!ValidText(media.SourceId, 64)) return null;
                    sources.Add(media.SourceId);
                }
                participants.Add(new TeamsParticipant(participant.Id!, user?.Id, user?.DisplayName,
                    participant.IsInLobby, participant.IsMuted, sources.Order(StringComparer.Ordinal).ToArray()));
            }
            // Source identifiers are call-scoped and can change. Never expose a
            // conflicting snapshot as a usable one-to-one identity mapping.
            if (participants.SelectMany(p => p.AudioSourceIds).GroupBy(id => id, StringComparer.Ordinal)
                .Any(group => group.Count() > 1)) return null;
            if (calls.Read(callId) != "established" || calls.ReadMeetingId(callId) != meetingId) return null;
            return new TeamsParticipantRoster(callId, meetingId, DateTimeOffset.UtcNow, participants);
        }
        catch (JsonException) { return null; }
        catch (HttpRequestException) { return null; }
        catch (IOException) { return null; }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested) { return null; }
    }

    private static bool ValidText(string? text, int maximum) => !string.IsNullOrWhiteSpace(text)
        && text.Length <= maximum && !text.Any(char.IsControl);

    private sealed record GraphRoster(GraphParticipant?[]? Value,
        [property: JsonPropertyName("@odata.nextLink")] string? NextLink);
    private sealed record GraphParticipant(string? Id, GraphInfo? Info,
        GraphMedia?[]? MediaStreams, bool? IsInLobby, bool? IsMuted);
    private sealed record GraphInfo(GraphIdentity? Identity);
    private sealed record GraphIdentity(GraphUser? User);
    private sealed record GraphUser(string? Id, string? DisplayName);
    private sealed record GraphMedia(string? MediaType, string? SourceId);
}

public interface ITeamsParticipantRosterClient
{
    Task<TeamsParticipantRoster?> ReadAsync(string callId, CancellationToken cancellationToken);
}

public sealed record TeamsParticipant(string ParticipantId, string? UserId, string? DisplayName,
    bool? IsInLobby, bool? IsMuted, IReadOnlyList<string> AudioSourceIds);

public sealed record TeamsParticipantRoster(string CallId, Guid MeetingId, DateTimeOffset ObservedAt,
    IReadOnlyList<TeamsParticipant> Participants)
{
    public string AttributionStatus => "roster-only-live-media-not-connected";
}
