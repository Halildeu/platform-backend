using System.Net;
using System.Text;
using System.Text.Json;
using Microsoft.Extensions.Options;
using TeamsCapture.Worker;
using Xunit;

namespace TeamsCapture.Worker.Tests;

public sealed class GraphTeamsParticipantRosterClientTests
{
    private const string Roster = """
        {"value":[
          {"id":"participant-17","info":{"identity":{"user":{"id":"user-17","displayName":"Test Speaker A"}}},
           "isInLobby":false,"isMuted":false,"mediaStreams":[
             {"mediaType":"audio","sourceId":"42"},{"mediaType":"video","sourceId":"99"}]},
          {"id":"participant-18","info":{"identity":{"phone":{"id":"not-a-verified-person"}}},
           "mediaStreams":[{"mediaType":"audio","sourceId":"43"}]}
        ]}
        """;

    [Fact]
    public async Task Reads_exact_call_scoped_audio_sources_without_inventing_identity_or_speaking_activity()
    {
        var handler = new Handler(Roster);
        var state = RegisteredCall();
        var result = await Client(handler, state).ReadAsync("call-1", default);
        Assert.NotNull(result);
        Assert.Equal(state.ReadMeetingId("call-1"), result.MeetingId);
        Assert.Equal("call-1", result.CallId);
        Assert.Equal("roster-only-live-media-not-connected", result.AttributionStatus);
        var named = result.Participants[0];
        Assert.Equal("participant-17", named.ParticipantId);
        Assert.Equal("user-17", named.UserId);
        Assert.Equal("Test Speaker A", named.DisplayName);
        Assert.Equal(["42"], named.AudioSourceIds);
        Assert.Null(result.Participants[1].DisplayName);
        Assert.Null(result.Participants[1].UserId);
        Assert.Null(result.Participants[1].IsMuted);
        Assert.Equal("https://graph.microsoft.com/v1.0/communications/calls/call-1/participants", handler.Uri);
        Assert.Equal(HttpMethod.Get, handler.Method);
        Assert.Equal("Bearer synthetic-token", handler.Authorization);
    }

    [Theory]
    [InlineData("unknown-call", "established")]
    [InlineData("call-1", "establishing")]
    [InlineData("call-1", "terminated")]
    [InlineData("call-1?x=anything", "established")]
    public async Task Does_not_query_unregistered_inactive_or_invalid_calls(string callId, string status)
    {
        var handler = new Handler(Roster);
        Assert.Null(await Client(handler, RegisteredCall(status)).ReadAsync(callId, default));
        Assert.Equal(0, handler.Requests);
    }

    [Fact]
    public async Task Disabled_configuration_does_not_request_token_or_roster()
    {
        var handler = new Handler(Roster);
        var tokens = new Tokens();
        var client = new GraphTeamsParticipantRosterClient(new HttpClient(handler),
            Options.Create(new TeamsCaptureOptions()), tokens, RegisteredCall());
        Assert.Null(await client.ReadAsync("call-1", default));
        Assert.Equal(0, tokens.Requests);
        Assert.Equal(0, handler.Requests);
    }

    [Theory]
    [InlineData("[]")]
    [InlineData("{}")]
    [InlineData("{\"value\":[null]}")]
    [InlineData("{\"value\":[],\"@odata.nextLink\":\"https://untrusted.example/next\"}")]
    [InlineData("{\"value\":[{\"id\":\"p\",\"mediaStreams\":[{\"mediaType\":\"audio\",\"sourceId\":42}]}]}")]
    [InlineData("{\"value\":[{\"id\":\"p\",\"mediaStreams\":[]},{\"id\":\"p\",\"mediaStreams\":[]}]}")]
    [InlineData("{\"value\":[{\"id\":\"p\",\"mediaStreams\":[{\"mediaType\":\"audio\",\"sourceId\":\"42\"}]},{\"id\":\"q\",\"mediaStreams\":[{\"mediaType\":\"audio\",\"sourceId\":\"42\"}]}]}")]
    public async Task Rejects_partial_malformed_or_ambiguous_rosters_without_following_links(string json)
    {
        var handler = new Handler(json);
        Assert.Null(await Client(handler, RegisteredCall()).ReadAsync("call-1", default));
        Assert.Equal(1, handler.Requests);
    }

    [Fact]
    public async Task Rejects_oversized_roster_even_when_content_length_is_not_available()
    {
        var handler = new Handler(new string(' ', 1024 * 1024 + 1)) { WithoutContentLength = true };
        Assert.Null(await Client(handler, RegisteredCall()).ReadAsync("call-1", default));
    }

    [Fact]
    public async Task Empty_roster_is_valid_but_cannot_identify_a_speaker()
    {
        var roster = await Client(new Handler("{\"value\":[]}"), RegisteredCall()).ReadAsync("call-1", default);
        Assert.NotNull(roster);
        Assert.Empty(roster.Participants);
    }

    [Theory]
    [InlineData(HttpStatusCode.Forbidden)]
    [InlineData(HttpStatusCode.TooManyRequests)]
    [InlineData(HttpStatusCode.Redirect)]
    public async Task Graph_failure_or_redirect_is_not_a_successful_empty_roster(HttpStatusCode status)
    {
        var handler = new Handler(Roster) { Status = status };
        Assert.Null(await Client(handler, RegisteredCall()).ReadAsync("call-1", default));
    }

    [Fact]
    public async Task Does_not_return_identity_after_call_terminates_during_Graph_request()
    {
        var state = RegisteredCall();
        var handler = new Handler(Roster) { OnRequest = () => state.Apply(Event("terminated")) };
        Assert.Null(await Client(handler, state).ReadAsync("call-1", default));
    }

    [Fact]
    public async Task Caller_cancellation_is_propagated()
    {
        using var cancellation = new CancellationTokenSource();
        cancellation.Cancel();
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() =>
            Client(new Handler(Roster), RegisteredCall()).ReadAsync("call-1", cancellation.Token));
    }

    private static GraphTeamsParticipantRosterClient Client(Handler handler, TeamsCallbackState state) => new(
        new HttpClient(handler), Options.Create(new TeamsCaptureOptions
        {
            Enabled = true, TenantId = Guid.NewGuid().ToString(), ApplicationId = Guid.NewGuid().ToString(),
            PublicCallbackBaseUrl = "https://bot.test.example", ControlApiKey = new string('k', 32),
            CallStateFilePath = Path.GetFullPath("unused-calls.json"),
            CalendarStateFilePath = Path.GetFullPath("unused-calendar.json")
        }), new Tokens(), state);

    private static TeamsCallbackState RegisteredCall(string status = "established")
    {
        var state = new TeamsCallbackState();
        state.Register("call-1", Guid.NewGuid());
        state.Apply(Event(status));
        return state;
    }

    private static JsonElement Event(string status) => JsonSerializer.SerializeToElement(new
    {
        value = new[] { new { resourceUrl = "/communications/calls/call-1", resourceData = new { state = status } } }
    });

    private sealed class Tokens : ITeamsAccessTokenProvider
    {
        public int Requests { get; private set; }
        public Task<string?> GetAccessTokenAsync(CancellationToken cancellationToken)
        {
            cancellationToken.ThrowIfCancellationRequested();
            Requests++;
            return Task.FromResult<string?>("synthetic-token");
        }
    }

    private sealed class Handler(string response) : HttpMessageHandler
    {
        public int Requests { get; private set; }
        public string? Uri { get; private set; }
        public HttpMethod? Method { get; private set; }
        public string? Authorization { get; private set; }
        public HttpStatusCode Status { get; init; } = HttpStatusCode.OK;
        public bool WithoutContentLength { get; init; }
        public Action? OnRequest { get; init; }

        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            Requests++;
            Uri = request.RequestUri?.AbsoluteUri;
            Method = request.Method;
            Authorization = request.Headers.Authorization?.ToString();
            OnRequest?.Invoke();
            return Task.FromResult(new HttpResponseMessage(Status)
            {
                Content = WithoutContentLength
                    ? new UnboundedLengthContent(response)
                    : new StringContent(response, Encoding.UTF8, "application/json")
            });
        }
    }

    private sealed class UnboundedLengthContent(string value) : HttpContent
    {
        protected override Task SerializeToStreamAsync(Stream stream, TransportContext? context) =>
            stream.WriteAsync(Encoding.UTF8.GetBytes(value)).AsTask();
        protected override bool TryComputeLength(out long length) { length = 0; return false; }
        protected override Task<Stream> CreateContentReadStreamAsync() =>
            Task.FromResult<Stream>(new MemoryStream(Encoding.UTF8.GetBytes(value)));
    }
}
