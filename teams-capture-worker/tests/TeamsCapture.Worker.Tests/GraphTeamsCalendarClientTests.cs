using System.Net;
using System.Text.Json;
using Microsoft.Extensions.Options;
using TeamsCapture.Worker;
using Xunit;

namespace TeamsCapture.Worker.Tests;

public sealed class GraphTeamsCalendarClientTests
{
    private static readonly Guid Organizer = Guid.Parse("f245954a-d956-4ea9-b590-d59e8ea24a40");
    private const string JoinUrl = "https://teams.microsoft.com/meet/12345?p=opaque'value";
    private const string Event = """
        {"id":"AAMk+/=","type":"singleInstance","isCancelled":false,"isOnlineMeeting":true,
        "onlineMeetingProvider":"teamsForBusiness","onlineMeeting":{"joinUrl":"https://teams.microsoft.com/meet/123?p=x"},
        "start":{"dateTime":"2026-09-24T12:00:00.0000000","timeZone":"UTC"},
        "end":{"dateTime":"2026-09-24T13:00:00.0000000","timeZone":"UTC"}}
        """;

    [Fact]
    public async Task Reads_exact_selected_event_with_encoded_id_and_UTC_without_reading_body_or_attendees()
    {
        var handler = new Handler(Event);
        var result = await Client(handler).ReadAsync(Organizer, "AAMk+/=", default);
        Assert.NotNull(result);
        Assert.Equal(DateTimeOffset.Parse("2026-09-24T12:00:00Z"), result.StartsAt);
        Assert.False(result.Cancelled);
        Assert.Contains("/events/AAMk%2B%2F%3D?", handler.Uri);
        Assert.DoesNotContain("body", handler.Uri);
        Assert.Equal("outlook.timezone=\"UTC\"", handler.Prefer);
        Assert.Equal("Bearer synthetic", handler.Authorization);
    }

    [Theory]
    [InlineData("seriesMaster", "singleInstance")]
    [InlineData("Europe/Istanbul", "UTC")]
    [InlineData("other", "AAMk+/=")]
    [InlineData("skypeForBusiness", "teamsForBusiness")]
    public async Task Rejects_unsupported_or_mismatched_events(string replacement, string original)
    {
        Assert.Null(await Client(new Handler(Event.Replace(original, replacement)))
            .ReadAsync(Organizer, "AAMk+/=", default));
    }

    [Fact]
    public async Task Cancellation_is_returned_without_requiring_online_meeting_details()
    {
        var client = Client(new Handler("{\"id\":\"event\",\"type\":\"occurrence\",\"isCancelled\":true}"));
        Assert.True((await client.ReadAsync(Organizer, "event", default))!.Cancelled);
    }

    [Fact]
    public async Task Non_allowlisted_organizer_and_invalid_event_never_request_a_token_or_Graph()
    {
        var handler = new Handler(Event);
        var tokens = new Tokens();
        var client = Client(handler, tokens);
        Assert.Null(await client.ReadAsync(Guid.NewGuid(), "event", default));
        Assert.Null(await client.ReadAsync(Organizer, "../other?query", default));
        Assert.Null(await client.ResolveMeetingAsync(Guid.NewGuid(), JoinUrl, default));
        Assert.Equal(0, tokens.Count);
        Assert.Equal(0, handler.Count);
    }

    [Fact]
    public async Task Resolves_opaque_join_URL_through_Graph_and_checks_organizer()
    {
        var handler = new Handler(Meeting(Organizer));
        var result = await Client(handler).ResolveMeetingAsync(Organizer, JoinUrl, default);
        Assert.Equal(new ScheduledTeamsMeeting("19:test@thread.v2", "0", Organizer.ToString()), result);
        var query = Uri.UnescapeDataString(new Uri(handler.Uri!).Query);
        Assert.Equal("?$filter=JoinWebUrl eq '" + JoinUrl.Replace("'", "''") + "'", query);
        Assert.Equal("graph.microsoft.com", new Uri(handler.Uri!).Host);
        Assert.Null(await Client(new Handler(Meeting(Guid.NewGuid()))).ResolveMeetingAsync(Organizer, JoinUrl, default));
    }

    [Theory]
    [InlineData("[]")]
    [InlineData("{\"value\":[null]}")]
    [InlineData("{\"value\":[],\"@odata.nextLink\":\"https://untrusted.example\"}")]
    [InlineData("{\"value\":[{\"joinWebUrl\":\"https://teams.microsoft.com/meet/12345?p=opaque'value\",\"chatInfo\":{},\"participants\":42}]}")]
    public async Task Malformed_or_partial_meeting_response_is_rejected(string body)
    {
        Assert.Null(await Client(new Handler(body)).ResolveMeetingAsync(Organizer, JoinUrl, default));
    }

    [Theory]
    [InlineData(HttpStatusCode.Forbidden)]
    [InlineData(HttpStatusCode.Redirect)]
    [InlineData(HttpStatusCode.NotFound)]
    [InlineData(HttpStatusCode.TooManyRequests)]
    public async Task Failed_calendar_read_never_becomes_permission_to_join(HttpStatusCode status)
    {
        Assert.Null(await Client(new Handler(Event) { Status = status }).ReadAsync(Organizer, "AAMk+/=", default));
    }

    [Fact]
    public async Task Oversized_payload_is_bounded()
    {
        Assert.Null(await Client(new Handler(new string(' ', 1024 * 1024 + 1))).ReadAsync(Organizer, "event", default));
    }

    private static string Meeting(Guid organizer) => JsonSerializer.Serialize(new { value = new[] { new {
        joinWebUrl = JoinUrl, chatInfo = new { threadId = "19:test@thread.v2", messageId = "0" },
        participants = new { organizer = new { identity = new { user = new { id = organizer.ToString() } } } }
    } } });

    private static GraphTeamsCalendarClient Client(Handler handler, Tokens? tokens = null) => new(new HttpClient(handler),
        tokens ?? new Tokens(), Options.Create(new TeamsCaptureOptions {
            Enabled = true, TenantId = Guid.NewGuid().ToString(), ApplicationId = Guid.NewGuid().ToString(),
            ClientSecret = "synthetic", ControlApiKey = new string('x', 32), PublicCallbackBaseUrl = "https://bot.test.example",
            CallStateFilePath = Path.GetFullPath("synthetic-calls"), CalendarStateFilePath = Path.GetFullPath("synthetic-calendar"),
            CalendarSchedulingEnabled = true, CalendarOrganizerIds = [Organizer],
            CalendarScheduleStateFilePath = Path.GetFullPath("synthetic-schedules")
        }));
    private sealed class Tokens : ITeamsAccessTokenProvider
    {
        public int Count;
        public Task<string?> GetAccessTokenAsync(CancellationToken cancellationToken) { Count++; return Task.FromResult<string?>("synthetic"); }
    }
    private sealed class Handler(string body) : HttpMessageHandler
    {
        public int Count;
        public string? Uri, Prefer, Authorization;
        public HttpStatusCode Status = HttpStatusCode.OK;
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            Count++; Uri = request.RequestUri!.AbsoluteUri; Prefer = request.Headers.GetValues("Prefer").Single();
            Authorization = request.Headers.Authorization!.ToString();
            return Task.FromResult(new HttpResponseMessage(Status) { Content = new StringContent(body) });
        }
    }
}
