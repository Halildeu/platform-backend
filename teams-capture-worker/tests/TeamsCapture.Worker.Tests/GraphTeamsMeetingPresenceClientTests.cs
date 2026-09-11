using System.Net;
using System.Text;
using System.Text.Json;
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

        var result = await client.JoinAsync(Command(), CancellationToken.None);

        Assert.Null(result);
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
            options,
            new CalendarResolver(),
            new AccessTokenProvider(),
            new HttpClient(handler));

    private static TeamsCaptureOptions ReadyOptions() => new()
    {
        Enabled = true,
        TenantId = Guid.NewGuid().ToString(),
        ApplicationId = Guid.NewGuid().ToString(),
        PublicCallbackBaseUrl = "https://bot.test.example"
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
