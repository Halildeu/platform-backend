using System.Net;
using System.Text.Json;
using Microsoft.Extensions.Options;
using TeamsCapture.Worker;
using Xunit;

namespace TeamsCapture.Worker.Tests;

public sealed class GraphTeamsCalendarBrowseTests
{
    private static readonly Guid Organizer = Guid.Parse("f245954a-d956-4ea9-b590-d59e8ea24a40");
    private static readonly DateTimeOffset From = DateTimeOffset.Parse("2026-09-24T12:00:00Z");
    private static readonly DateTimeOffset To = From.AddDays(1);
    private static string Next(string query = "$skiptoken=opaque") => $"https://graph.microsoft.com/v1.0/users/{Organizer}/calendar/calendarView?{query}";
    private static object Event(string id = "AAMk+/=", bool owns = true, bool cancelled = false, bool online = true) => new {
        id, subject = "Müşteri sunumu", type = "occurrence", isCancelled = cancelled, isOnlineMeeting = online, isOrganizer = owns,
        onlineMeetingProvider = "teamsForBusiness", start = new { dateTime = "2026-09-24T13:00:00", timeZone = "UTC" },
        end = new { dateTime = "2026-09-24T14:00:00", timeZone = "UTC" }
    };
    private static string Page(object[] items, string? next = null) {
        var page = new Dictionary<string, object> { ["value"] = items };
        if (next is not null) page["@odata.nextLink"] = next;
        return JsonSerializer.Serialize(page);
    }

    [Fact]
    public async Task Follows_same_mailbox_pages_and_returns_minimal_choices_without_joining()
    {
        var handler = new Handler(Page([Event(), Event("cancelled", cancelled: true), Event("guest", owns: false), Event("offline", online: false)], Next()),
            Page([Event("second")]));
        var result = await Client(handler).BrowseAsync(Organizer, From, To, default);
        Assert.NotNull(result); Assert.False(result.Truncated); Assert.Equal(2, result.Items.Count);
        Assert.Equal("Müşteri sunumu", result.Items[0].Title);
        Assert.Contains("startDateTime=2026-09-24T12%3A00%3A00", handler.Uris[0]);
        Assert.Contains("$select=id,subject", handler.Uris[0]);
        Assert.DoesNotContain("attendees", handler.Uris[0]); Assert.DoesNotContain("body", handler.Uris[0]);
        Assert.Equal(Next(), handler.Uris[1]);
        Assert.All(handler.Methods, method => Assert.Equal(HttpMethod.Get, method));
        Assert.DoesNotContain("joinUrl", JsonSerializer.Serialize(result));
    }

    [Theory]
    [InlineData("https://untrusted.example/steal")]
    [InlineData("http://graph.microsoft.com/v1.0/users/a/calendar/calendarView?x=1")]
    [InlineData("https://graph.microsoft.com/v1.0/users/another/calendar/calendarView?$skiptoken=x")]
    [InlineData("https://graph.microsoft.com/v1.0/me/messages?$skiptoken=x")]
    public async Task Rejects_foreign_continuation_without_sending_token(string next)
    {
        var handler = new Handler(Page([Event()], next));
        Assert.Null(await Client(handler).BrowseAsync(Organizer, From, To, default));
        Assert.Single(handler.Uris);
    }

    [Fact]
    public async Task Detects_continuation_loops_and_duplicate_event_ids()
    {
        var loop = new Handler(Page([], Next()), Page([], Next()));
        Assert.Null(await Client(loop).BrowseAsync(Organizer, From, To, default)); Assert.Equal(2, loop.Uris.Count);
        var duplicate = new Handler(Page([Event()], Next()), Page([Event()]));
        Assert.Null(await Client(duplicate).BrowseAsync(Organizer, From, To, default));
    }

    [Fact]
    public async Task Bounded_page_limit_is_explicitly_truncated()
    {
        var handler = new Handler(Enumerable.Range(0, 5).Select(i => Page([Event("event" + i)], Next("$skiptoken=" + i))).ToArray());
        var result = await Client(handler).BrowseAsync(Organizer, From, To, default);
        Assert.NotNull(result); Assert.True(result.Truncated); Assert.Equal(5, result.Items.Count); Assert.Equal(5, handler.Uris.Count);
    }

    [Theory]
    [InlineData("{}")]
    [InlineData("[]")]
    [InlineData("null")]
    [InlineData("{\"value\":42}")]
    [InlineData("{\"value\":[{}]}")]
    [InlineData("{\"value\":[null]}")]
    public async Task Malformed_response_is_unavailable_not_an_empty_calendar(string body)
    {
        Assert.Null(await Client(new Handler(body)).BrowseAsync(Organizer, From, To, default));
    }

    [Theory]
    [InlineData(HttpStatusCode.Forbidden)]
    [InlineData(HttpStatusCode.Redirect)]
    [InlineData(HttpStatusCode.TooManyRequests)]
    public async Task Graph_denial_is_not_a_successful_empty_result(HttpStatusCode status)
    {
        Assert.Null(await Client(new Handler("{}") { Status = status }).BrowseAsync(Organizer, From, To, default));
    }

    [Fact]
    public async Task Invalid_window_and_unlisted_organizer_make_no_network_requests()
    {
        var handler = new Handler("{}"); var client = Client(handler);
        Assert.Null(await client.BrowseAsync(Guid.NewGuid(), From, To, default));
        Assert.Null(await client.BrowseAsync(Organizer, To, From, default));
        Assert.Null(await client.BrowseAsync(Organizer, From, From.AddDays(32), default));
        Assert.Empty(handler.Uris);
    }

    [Fact]
    public async Task Only_starts_in_requested_window_are_offered_and_input_is_UTC_normalized()
    {
        var handler = new Handler(Page([Event()]));
        var result = await Client(handler).BrowseAsync(Organizer, From.AddHours(2).ToOffset(TimeSpan.FromHours(3)), To, default);
        Assert.NotNull(result); Assert.Empty(result.Items);
        Assert.Contains("startDateTime=2026-09-24T14%3A00%3A00", handler.Uris[0]);
    }

    [Fact]
    public async Task Oversized_response_is_bounded()
    {
        Assert.Null(await Client(new Handler(new string(' ', 1024 * 1024 + 1))).BrowseAsync(Organizer, From, To, default));
    }

    private static GraphTeamsCalendarClient Client(Handler handler) => new(new HttpClient(handler), new Tokens(), Options.Create(new TeamsCaptureOptions {
        Enabled = true, TenantId = Guid.NewGuid().ToString(), ApplicationId = Guid.NewGuid().ToString(),
        ClientSecret = "synthetic", ControlApiKey = new string('x', 32), PublicCallbackBaseUrl = "https://bot.test.example",
        CallStateFilePath = Path.GetFullPath("synthetic-calls"), CalendarStateFilePath = Path.GetFullPath("synthetic-calendar"),
        CalendarSchedulingEnabled = true, CalendarOrganizerIds = [Organizer], CalendarScheduleStateFilePath = Path.GetFullPath("synthetic-schedules")
    }));
    private sealed class Tokens : ITeamsAccessTokenProvider {
        public Task<string?> GetAccessTokenAsync(CancellationToken token) { token.ThrowIfCancellationRequested(); return Task.FromResult<string?>("synthetic"); }
    }
    private sealed class Handler(params string[] bodies) : HttpMessageHandler {
        public List<string> Uris = []; public List<HttpMethod> Methods = [];
        public HttpStatusCode Status = HttpStatusCode.OK;
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken token) {
            Assert.Equal("Bearer synthetic", request.Headers.Authorization!.ToString());
            Assert.Equal("outlook.timezone=\"UTC\"", request.Headers.GetValues("Prefer").Single());
            Uris.Add(request.RequestUri!.AbsoluteUri); Methods.Add(request.Method);
            return Task.FromResult(new HttpResponseMessage(Status) { Content = new StringContent(bodies[Uris.Count - 1]) });
        }
    }
}
