using System.Net;
using System.Net.Http.Headers;
using Microsoft.Extensions.Options;

namespace TeamsCapture.Worker;

public enum TeamsCallOperationResult { Succeeded, Ended, Unconfirmed }

public interface ITeamsCallLifecycleClient
{
    Task<TeamsCallOperationResult> KeepAliveAsync(string callId, CancellationToken cancellationToken);
    Task<TeamsCallOperationResult> LeaveAsync(string callId, CancellationToken cancellationToken);
}

// Transport is internal: HTTP endpoints and maintenance restrict operations to owned calls.
// Coordinator may also remove the call just created when its durable commit fails.
public sealed class GraphTeamsCallLifecycleClient(HttpClient client, ITeamsAccessTokenProvider tokenProvider,
    IOptions<TeamsCaptureOptions> settings) : ITeamsCallLifecycleClient
{
    public Task<TeamsCallOperationResult> KeepAliveAsync(string callId, CancellationToken cancellationToken) =>
        SendAsync(callId, HttpMethod.Post, "/keepAlive", HttpStatusCode.OK, cancellationToken);

    public Task<TeamsCallOperationResult> LeaveAsync(string callId, CancellationToken cancellationToken) =>
        SendAsync(callId, HttpMethod.Delete, "", HttpStatusCode.NoContent, cancellationToken);

    private async Task<TeamsCallOperationResult> SendAsync(string callId, HttpMethod method, string suffix,
        HttpStatusCode success, CancellationToken cancellationToken)
    {
        if (!settings.Value.IsReadyForRegistration() || !TeamsCallbackState.ValidCallId(callId))
            return TeamsCallOperationResult.Unconfirmed;
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        deadline.CancelAfter(TimeSpan.FromSeconds(15));
        try
        {
            var token = await tokenProvider.GetAccessTokenAsync(deadline.Token).ConfigureAwait(false);
            if (string.IsNullOrWhiteSpace(token)) return TeamsCallOperationResult.Unconfirmed;
            using var request = new HttpRequestMessage(method,
                $"https://graph.microsoft.com/v1.0/communications/calls/{callId}{suffix}");
            request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", token);
            using var response = await client.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, deadline.Token).ConfigureAwait(false);
            return response.StatusCode == success ? TeamsCallOperationResult.Succeeded
                : response.StatusCode == HttpStatusCode.NotFound ? TeamsCallOperationResult.Ended
                : TeamsCallOperationResult.Unconfirmed;
        }
        catch (Exception error) when (error is HttpRequestException or OperationCanceledException)
        {
            return TeamsCallOperationResult.Unconfirmed;
        }
    }
}
