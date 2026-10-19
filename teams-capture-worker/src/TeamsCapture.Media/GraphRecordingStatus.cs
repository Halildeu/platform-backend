using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;

namespace TeamsCapture.Media;

/// <summary>Receipt can only be issued by the validated HTTP success path.</summary>
public sealed class RecordingStatusReceipt
{
    public MediaCallKey Call { get; }
    internal RecordingStatusReceipt(MediaCallKey call) { Call = call; }
}

public interface IGraphRecordingStatus
{
    Task<RecordingStatusReceipt> StartAsync(MediaCallKey call, CancellationToken cancellationToken);
    Task EndAsync(MediaCallKey call, CancellationToken cancellationToken);
}

/// <summary>
/// Requires explicit Microsoft recording-status success, not merely SDK task completion.
/// Native media/call ownership and tenant policy readiness must be checked by the host before use.
/// </summary>
public sealed class GraphRecordingStatus : IGraphRecordingStatus, IDisposable
{
    private readonly HttpClient http;
    private readonly Func<CancellationToken, Task<string>> graphToken;

    public GraphRecordingStatus(Func<CancellationToken, Task<string>> graphToken)
        : this(new HttpClient(new HttpClientHandler { AllowAutoRedirect = false, UseCookies = false })
            { Timeout = TimeSpan.FromSeconds(10) }, graphToken) { }
    internal GraphRecordingStatus(HttpClient http, Func<CancellationToken, Task<string>> graphToken)
    { this.http = http; this.graphToken = graphToken; }

    public async Task<RecordingStatusReceipt> StartAsync(MediaCallKey call, CancellationToken cancellationToken)
    {
        await UpdateAsync(call, "recording", cancellationToken).ConfigureAwait(false);
        return new RecordingStatusReceipt(call);
    }
    public Task EndAsync(MediaCallKey call, CancellationToken cancellationToken) => UpdateAsync(call, "notRecording", cancellationToken);

    private async Task UpdateAsync(MediaCallKey call, string status, CancellationToken cancellationToken)
    {
        if (call.TenantId == Guid.Empty || call.MeetingId == Guid.Empty || call.MediaSessionId == Guid.Empty
            || call.CallId is not { Length: > 0 and <= 128 }
            || !call.CallId.All(c => char.IsAsciiLetterOrDigit(c) || c is '_' or '.' or ':' or '-'))
            throw new ArgumentException("Invalid Microsoft call binding.");
        var callPath = "https://graph.microsoft.com/v1.0/communications/calls/" + Uri.EscapeDataString(call.CallId);
        string context = Guid.NewGuid().ToString("D");
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        deadline.CancelAfter(TimeSpan.FromSeconds(10));
        using var request = new HttpRequestMessage(HttpMethod.Post, callPath + "/updateRecordingStatus");
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", await graphToken(deadline.Token).ConfigureAwait(false));
        request.Content = JsonContent.Create(new { status, clientContext = context });
        using var response = await http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, deadline.Token).ConfigureAwait(false);
        if (response.StatusCode != HttpStatusCode.OK || response.Content.Headers.ContentLength > 16384)
            throw new InvalidOperationException("Microsoft recording status is unconfirmed.");
        await using var stream = await response.Content.ReadAsStreamAsync(deadline.Token).ConfigureAwait(false);
        // Bounded read even for a chunked response with no Content-Length.
        var bytes = new byte[16385]; int total = 0, read;
        while (total < bytes.Length && (read = await stream.ReadAsync(bytes.AsMemory(total), deadline.Token).ConfigureAwait(false)) > 0) total += read;
        if (total > 16384) throw new InvalidOperationException("Oversized Microsoft recording response.");
        using var json = JsonDocument.Parse(bytes.AsMemory(0, total), new JsonDocumentOptions { MaxDepth = 12 });
        var root = json.RootElement;
        if (root.ValueKind != JsonValueKind.Object
            || Text(root, "@odata.type") != "#microsoft.graph.updateRecordingStatusOperation"
            || Text(root, "status") != "completed" || Text(root, "clientContext") != context
            || Text(root, "id") is not { Length: > 0 and <= 128 } operation
            || !operation.All(c => char.IsAsciiLetterOrDigit(c) || c is '-' or '_')
            || root.TryGetProperty("resultInfo", out var result) && result.ValueKind != JsonValueKind.Null
            || response.Headers.Location?.AbsoluteUri != callPath + "/operations/" + operation)
            throw new InvalidOperationException("Microsoft recording status is unconfirmed.");
        cancellationToken.ThrowIfCancellationRequested();
    }
    private static string? Text(JsonElement value, string name) => value.TryGetProperty(name, out var field)
        && field.ValueKind == JsonValueKind.String ? field.GetString() : null;
    public void Dispose() => http.Dispose();
}
