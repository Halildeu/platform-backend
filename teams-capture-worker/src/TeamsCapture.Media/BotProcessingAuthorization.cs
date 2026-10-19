using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text;
using System.Text.Json;

namespace TeamsCapture.Media;

public sealed record BotRecordingBinding(string WorkerClientId, string CallId, string MediaSessionId);
public sealed record BotProcessingRequest(Guid IntentId, long Revision, BotRecordingBinding Binding);
public sealed record BotProcessingCheck(int SchemaVersion, string Purpose, Guid IntentId, Guid MeetingId,
    long Revision, BotRecordingBinding Binding, Guid TenantId, Guid OrgId, long CompanyId, long UserId,
    Guid MicrosoftTenantId, Guid OrganizerId, DateTimeOffset CheckedAt, DateTimeOffset ExpiresAt);

public interface IBotProcessingAuthorization
{
    // A point-in-time check. Call again at dispatch/output boundaries; never cache positive decisions.
    Task<BotProcessingCheck> CheckAsync(MediaCallKey call, Guid intentId, CancellationToken cancellationToken);
}

public sealed record BotProcessingOptions(bool Enabled, Uri? TokenEndpoint, Uri? MeetingServiceBaseUrl, string? ClientSecret);

/// <summary>Separate native-host SERVICE identity. Never manufactures user JWT claims.</summary>
public sealed class BotProcessingAuthorization : IBotProcessingAuthorization, IDisposable
{
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web) { PropertyNameCaseInsensitive = false, MaxDepth = 16 };
    private readonly HttpClient http;
    private readonly BotProcessingOptions options;
    private readonly TimeProvider time;
    private readonly SemaphoreSlim mintLock = new(1, 1);
    private string? token;
    private DateTimeOffset tokenUntil;

    public BotProcessingAuthorization(BotProcessingOptions options)
        : this(options, new HttpClient(new HttpClientHandler { AllowAutoRedirect = false, UseCookies = false })
            { Timeout = TimeSpan.FromSeconds(3) }, TimeProvider.System) { }

    internal BotProcessingAuthorization(BotProcessingOptions options, HttpClient http, TimeProvider time)
    {
        this.options = options; this.http = http; this.time = time;
        if (options.Enabled && (!SafeEndpoint(options.TokenEndpoint) || !SafeEndpoint(options.MeetingServiceBaseUrl)
            || options.MeetingServiceBaseUrl!.AbsolutePath != "/" || string.IsNullOrWhiteSpace(options.ClientSecret)
            || options.ClientSecret.Length > 4096)) throw new ArgumentException("Invalid native processing configuration.");
    }

    public async Task<BotProcessingCheck> CheckAsync(MediaCallKey call, Guid intentId, CancellationToken cancellationToken)
    {
        if (!options.Enabled) throw new InvalidOperationException("Native processing is disabled.");
        if (intentId == Guid.Empty || call.TenantId == Guid.Empty || call.MeetingId == Guid.Empty || call.MediaSessionId == Guid.Empty
            || !Identifier(call.CallId)) throw new ArgumentException("Invalid native capture scope.");
        var binding = new BotRecordingBinding("teams-capture-worker", call.CallId, call.MediaSessionId.ToString("D"));
        using var deadline = CancellationTokenSource.CreateLinkedTokenSource(cancellationToken);
        deadline.CancelAfter(TimeSpan.FromSeconds(3));
        var started = time.GetUtcNow();
        using var request = new HttpRequestMessage(HttpMethod.Post,
            new Uri(options.MeetingServiceBaseUrl!, $"api/v1/internal/meetings/{call.MeetingId:D}/bot-recording/check-processing"));
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", await TokenAsync(deadline.Token).ConfigureAwait(false));
        request.Headers.CacheControl = new CacheControlHeaderValue { NoCache = true, NoStore = true };
        request.Content = JsonContent.Create(new BotProcessingRequest(intentId, 2, binding), options: Json);
        using var response = await http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, deadline.Token).ConfigureAwait(false);
        if (response.StatusCode != HttpStatusCode.OK) throw new InvalidOperationException("Native processing authorization denied or unavailable.");
        var check = await ReadAsync<BotProcessingCheck>(response, deadline.Token).ConfigureAwait(false);
        var now = time.GetUtcNow();
        if (check is null || check.SchemaVersion != 1 || check.Purpose != "TEAMS_LIVE_TRANSCRIPTION"
            || check.IntentId != intentId || check.MeetingId != call.MeetingId || check.Revision != 2 || check.Binding != binding
            || check.TenantId == Guid.Empty || check.OrgId == Guid.Empty || check.CompanyId <= 0 || check.UserId <= 0
            || check.MicrosoftTenantId != call.TenantId || check.OrganizerId == Guid.Empty
            || check.CheckedAt < started.AddSeconds(-2) || check.CheckedAt > now.AddSeconds(2)
            || check.CheckedAt >= check.ExpiresAt || now >= check.ExpiresAt || now - started > TimeSpan.FromSeconds(3))
            throw new InvalidOperationException("Invalid or stale native processing authorization.");
        cancellationToken.ThrowIfCancellationRequested();
        return check;
    }

    private async Task<string> TokenAsync(CancellationToken cancellationToken)
    {
        await mintLock.WaitAsync(cancellationToken).ConfigureAwait(false);
        try
        {
            if (token is not null && time.GetUtcNow() < tokenUntil) return token;
            using var request = new HttpRequestMessage(HttpMethod.Post, options.TokenEndpoint);
            request.Headers.Authorization = new AuthenticationHeaderValue("Basic", Convert.ToBase64String(
                Encoding.UTF8.GetBytes("teams-capture-media:" + options.ClientSecret)));
            request.Content = new FormUrlEncodedContent(new Dictionary<string, string> {
                ["grant_type"] = "client_credentials", ["audience"] = "meeting-service", ["permissions"] = "meeting:bot-recording:check"
            });
            var started = time.GetUtcNow();
            using var response = await http.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellationToken).ConfigureAwait(false);
            if (response.StatusCode != HttpStatusCode.OK) throw new InvalidOperationException("Native service identity unavailable.");
            var result = await ReadAsync<JsonElement>(response, cancellationToken).ConfigureAwait(false);
            if (!result.TryGetProperty("access_token", out var access) || access.ValueKind != JsonValueKind.String
                || access.GetString() is not { Length: > 0 and <= 16384 } value || value.Any(char.IsWhiteSpace)
                || !result.TryGetProperty("token_type", out var kind) || kind.GetString() != "Bearer"
                || !result.TryGetProperty("expires_in", out var expiry) || !expiry.TryGetInt32(out var seconds) || seconds is <= 10 or > 3600)
                throw new InvalidOperationException("Invalid native service identity response.");
            tokenUntil = started.AddSeconds(Math.Min(60, seconds - 10));
            if (tokenUntil <= time.GetUtcNow()) throw new InvalidOperationException("Expired native service identity response.");
            return token = value;
        }
        finally { mintLock.Release(); }
    }

    private static async Task<T?> ReadAsync<T>(HttpResponseMessage response, CancellationToken cancellationToken)
    {
        if (response.Content.Headers.ContentLength > 32768) throw new InvalidOperationException("Oversized authorization response.");
        await using var stream = await response.Content.ReadAsStreamAsync(cancellationToken).ConfigureAwait(false);
        using var buffer = new MemoryStream();
        var block = new byte[4096];
        int read;
        while ((read = await stream.ReadAsync(block, cancellationToken).ConfigureAwait(false)) > 0)
        {
            if (buffer.Length + read > 32768) throw new InvalidOperationException("Oversized authorization response.");
            buffer.Write(block, 0, read);
        }
        return JsonSerializer.Deserialize<T>(buffer.GetBuffer().AsSpan(0, checked((int)buffer.Length)), Json);
    }

    private static bool SafeEndpoint(Uri? value) => value is { IsAbsoluteUri: true, Scheme: "https" }
        && value.UserInfo.Length == 0 && value.Query.Length == 0 && value.Fragment.Length == 0;
    private static bool Identifier(string value) => value is { Length: > 0 and <= 128 }
        && value.All(c => char.IsAsciiLetterOrDigit(c) || c is '_' or '.' or ':' or '-');
    public void Dispose() { http.Dispose(); mintLock.Dispose(); token = null; }
}
