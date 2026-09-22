using System.Net;
using System.Text;
using System.Text.Json;
using Microsoft.Extensions.Options;
using TeamsCapture.Worker;
using Xunit;

namespace TeamsCapture.Worker.Tests;

public sealed class GraphTeamsMeetingPresenceClientTests
{
    [Fact]
    public async Task Disabled_tenant_never_calls_Graph()
    {
        var handler = new CapturingHandler("{}");
        var client = CreateClient(new TeamsCaptureOptions(), handler);

        await Assert.ThrowsAsync<TeamsJoinNotCreatedException>(() => client.JoinAsync(Command(), CancellationToken.None));
        Assert.False(handler.WasCalled);
    }

    [Fact]
    public async Task Uses_service_hosted_media_and_never_requests_raw_media()
    {
        var handler = new CapturingHandler("{\"id\":\"call-1\"}");
        var client = CreateClient(ReadyOptions(), handler);

        var result = await client.JoinAsync(Command(), CancellationToken.None);

        Assert.Equal("call-1", result?.CallId);
        using var document = JsonDocument.Parse(handler.Body!);
        var root = document.RootElement;
        Assert.Equal("#microsoft.graph.serviceHostedMediaConfig", root
            .GetProperty("mediaConfig").GetProperty("@odata.type").GetString());
        Assert.DoesNotContain("appHostedMediaConfig", handler.Body!, StringComparison.Ordinal);
    }

    private static GraphTeamsMeetingPresenceClient CreateClient(
        TeamsCaptureOptions options,
        CapturingHandler handler) => new(
            Options.Create(options),
            new CalendarResolver(),
            new AccessTokenProvider(),
            new HttpClient(handler));

    [Fact]
    public async Task Token_outage_does_not_lock_meeting_and_retry_creates_exactly_one_call()
    {
        using var fixture = new TeamsOperationalTests.Fixture();
        var tokens = new RecoveringTokenProvider();
        var handler = new CapturingHandler("{\"id\":\"call-1\"}");
        var client = new GraphTeamsMeetingPresenceClient(fixture.Options, new CalendarResolver(), tokens, new HttpClient(handler));
        var state = new TeamsCallbackState(fixture.Options);
        var coordinator = new TeamsMeetingPresenceCoordinator(state, new TeamsOperationalTests.LifecycleStub());
        var command = Command();
        Assert.Equal("join_not_created", (await coordinator.JoinAsync(command, client, CancellationToken.None)).FailureCode);
        Assert.False(handler.WasCalled);
        Assert.Null(state.ReadJoin(command.MeetingId));
        Assert.True((await coordinator.JoinAsync(command, client, CancellationToken.None)).Joined);
        Assert.True(handler.WasCalled);
    }

    private sealed class RecoveringTokenProvider : ITeamsAccessTokenProvider
    {
        private int attempts;
        public Task<string?> GetAccessTokenAsync(CancellationToken cancellationToken)
        {
            if (++attempts == 1) throw new HttpRequestException("synthetic token outage");
            return Task.FromResult<string?>("synthetic-token");
        }
    }

    private static TeamsCaptureOptions ReadyOptions() => new()
    {
        Enabled = true,
        ClientSecret = "synthetic-test-credential",
        TenantId = Guid.NewGuid().ToString(),
        ApplicationId = Guid.NewGuid().ToString(),
        PublicCallbackBaseUrl = "https://bot.test.example",
        ControlApiKey = new string('k', 32),
        CallStateFilePath = Path.GetFullPath("call-state-test.json"),
        CalendarStateFilePath = Path.GetFullPath("calendar-state-test.json")
    };

    private static MeetingPresenceCommand Command() => new(Guid.NewGuid(), "calendar-event-1", "corr-1");

    private sealed class CalendarResolver : ITeamsCalendarMeetingResolver
    {
        public Task<ScheduledTeamsMeeting?> ResolveAsync(string calendarEventId, CancellationToken cancellationToken) =>
            Task.FromResult<ScheduledTeamsMeeting?>(new ScheduledTeamsMeeting(
                "19:meeting_example@thread.v2", "0", Guid.NewGuid().ToString()));
    }

    private sealed class AccessTokenProvider : ITeamsAccessTokenProvider
    {
        public Task<string?> GetAccessTokenAsync(CancellationToken cancellationToken) =>
            Task.FromResult<string?>("test-access-token");
    }

    private sealed class CapturingHandler(string responseBody) : HttpMessageHandler
    {
        public bool WasCalled { get; private set; }

        public string? Body { get; private set; }

        protected override async Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken)
        {
            WasCalled = true;
            Body = request.Content is null ? null : await request.Content.ReadAsStringAsync(cancellationToken);
            return new HttpResponseMessage(HttpStatusCode.Created)
            {
                Content = new StringContent(responseBody, Encoding.UTF8, "application/json")
            };
        }
    }
}
