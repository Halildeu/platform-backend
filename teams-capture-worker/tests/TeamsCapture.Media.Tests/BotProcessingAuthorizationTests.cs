using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using TeamsCapture.Media;
using Xunit;

namespace TeamsCapture.Media.Tests;

public class BotProcessingAuthorizationTests
{
    private readonly MediaCallKey call = new(Guid.NewGuid(), Guid.NewGuid(), "call-1", Guid.NewGuid());
    private readonly Guid intent = Guid.NewGuid();
    private static readonly JsonSerializerOptions Json = new(JsonSerializerDefaults.Web);
    private BotProcessingCheck Check() => new(1, "TEAMS_LIVE_TRANSCRIPTION", intent, call.MeetingId, 2,
        new("teams-capture-worker", call.CallId, call.MediaSessionId.ToString("D")), Guid.NewGuid(), Guid.NewGuid(),
        35, 42, call.TenantId, Guid.NewGuid(), DateTimeOffset.UtcNow, DateTimeOffset.UtcNow.AddMinutes(2));

    [Fact] public async Task UsesSeparateServiceMintAndChecksEveryTimeWithoutCachingPositiveAuthorization()
    {
        int mint = 0, checks = 0;
        using var client = Client(async request => {
            if (request.RequestUri!.Host == "auth.example")
            {
                mint++; Assert.Equal("Basic", request.Headers.Authorization!.Scheme);
                Assert.Equal("teams-capture-media:synthetic", System.Text.Encoding.UTF8.GetString(Convert.FromBase64String(request.Headers.Authorization.Parameter!)));
                var body = await request.Content!.ReadAsStringAsync();
                Assert.Contains("audience=meeting-service", body); Assert.Contains("permissions=meeting%3Abot-recording%3Acheck", body);
                return Reply(new { access_token = "synthetic-token", token_type = "Bearer", expires_in = 120 });
            }
            checks++; Assert.Equal("https://meeting.example/api/v1/internal/meetings/" + call.MeetingId + "/bot-recording/check-processing", request.RequestUri.AbsoluteUri);
            Assert.Equal("synthetic-token", request.Headers.Authorization!.Parameter);
            var input = await request.Content!.ReadFromJsonAsync<BotProcessingRequest>();
            Assert.Equal(new BotProcessingRequest(intent, 2, new("teams-capture-worker", call.CallId, call.MediaSessionId.ToString("D"))), input);
            return Reply(Check());
        });
        await client.CheckAsync(call, intent, default); await client.CheckAsync(call, intent, default);
        Assert.Equal(1, mint); Assert.Equal(2, checks);
    }

    [Theory] [InlineData("call")] [InlineData("media")] [InlineData("meeting")] [InlineData("intent")]
    [InlineData("revision")] [InlineData("tenant")] [InlineData("scope")] [InlineData("old")] [InlineData("expiry")]
    public async Task RejectsForeignStaleOrMalformedAuthority(string fault)
    {
        var value = Check(); value = fault switch {
            "call" => value with { Binding = value.Binding with { CallId = "other" } },
            "media" => value with { Binding = value.Binding with { MediaSessionId = Guid.NewGuid().ToString("D") } },
            "meeting" => value with { MeetingId = Guid.NewGuid() }, "intent" => value with { IntentId = Guid.NewGuid() },
            "revision" => value with { Revision = 3 }, "tenant" => value with { MicrosoftTenantId = Guid.NewGuid() },
            "scope" => value with { TenantId = Guid.Empty }, "old" => value with { CheckedAt = DateTimeOffset.UtcNow.AddMinutes(-1) },
            _ => value with { ExpiresAt = DateTimeOffset.UtcNow.AddSeconds(-1) }
        };
        using var client = Client(request => Task.FromResult(request.RequestUri!.Host == "auth.example" ? Token() : Reply(value)));
        await Assert.ThrowsAsync<InvalidOperationException>(() => client.CheckAsync(call, intent, default));
    }

    [Theory] [InlineData(302)] [InlineData(401)] [InlineData(403)] [InlineData(404)] [InlineData(409)] [InlineData(503)]
    public async Task NeverTreatsUnavailableOrRedirectAsPermission(int status)
    {
        using var client = Client(request => Task.FromResult(request.RequestUri!.Host == "auth.example" ? Token() : new HttpResponseMessage((HttpStatusCode)status)));
        await Assert.ThrowsAsync<InvalidOperationException>(() => client.CheckAsync(call, intent, default));
    }

    [Fact] public async Task OversizedAndDisabledResponsesNeverReachAudio()
    {
        using var client = Client(request => Task.FromResult(request.RequestUri!.Host == "auth.example" ? Token() :
            new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(new string('x', 32769)) }));
        await Assert.ThrowsAsync<InvalidOperationException>(() => client.CheckAsync(call, intent, default));
        using var disabled = new BotProcessingAuthorization(new(false, null, null, null),
            new HttpClient(new Handler(_ => throw new Exception("Must not access network"))), TimeProvider.System);
        await Assert.ThrowsAsync<InvalidOperationException>(() => disabled.CheckAsync(call, intent, default));
    }
    private BotProcessingAuthorization Client(Func<HttpRequestMessage, Task<HttpResponseMessage>> send) => new(
        new(true, new("https://auth.example/oauth2/token"), new("https://meeting.example/"), "synthetic"), new HttpClient(new Handler(send)), TimeProvider.System);
    internal static HttpResponseMessage Reply<T>(T value) => new(HttpStatusCode.OK) { Content = JsonContent.Create(value, options: Json) };
    private static HttpResponseMessage Token() => Reply(new { access_token = "synthetic", token_type = "Bearer", expires_in = 120 });
    internal sealed class Handler(Func<HttpRequestMessage, Task<HttpResponseMessage>> send) : HttpMessageHandler
    { protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken) => send(request); }
}
