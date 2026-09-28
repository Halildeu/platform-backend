using System.Net;
using System.Text;
using System.Text.Json;
using Microsoft.Extensions.Options;
using TeamsCapture.Worker;
using Xunit;

namespace TeamsCapture.Worker.Tests;

public sealed class TeamsScheduleAuthorizationTests
{
    [Theory]
    [InlineData(ScheduleAuthorization.Denied, "schedule_authorization_denied")]
    [InlineData(ScheduleAuthorization.Unavailable, "schedule_authorization_unavailable")]
    public async Task Denial_or_outage_after_token_acquisition_never_sends_Graph_and_releases_reservation(
        ScheduleAuthorization outcome, string failure)
    {
        using var f = new Fixture();
        f.Token.BeforeReturn = () => f.Authorization.Result = outcome; // Permission changes during slow Entra acquisition.
        var result = await f.Join();
        Assert.Equal(failure, result.FailureCode); Assert.Equal(0, f.Graph.Calls);
        Assert.Null(f.Calls.ReadJoin(f.Command.MeetingId)); Assert.Equal(1, f.Authorization.Checks);
        f.Token.BeforeReturn = null; f.Authorization.Result = ScheduleAuthorization.Allowed;
        Assert.True((await f.Join()).Joined); Assert.Equal(1, f.Graph.Calls);
    }

    [Fact]
    public async Task Timeout_after_POST_remains_ambiguous_and_is_never_blindly_retried()
    {
        using var f = new Fixture(); f.Graph.Ambiguous = true;
        Assert.Equal("join_outcome_unconfirmed", (await f.Join()).FailureCode);
        Assert.NotNull(f.Calls.ReadJoin(f.Command.MeetingId));
        Assert.Equal("join_outcome_unconfirmed", (await f.Join()).FailureCode);
        Assert.Equal(1, f.Graph.Calls); Assert.Equal(1, f.Authorization.Checks);
    }

    [Theory]
    [InlineData("ended")]
    [InlineData("window")]
    [InlineData("cancelled")]
    public async Task Time_or_schedule_change_during_authorization_cannot_join(string change)
    {
        using var f = new Fixture();
        f.Authorization.DuringCheck = () => {
            if (change == "window") f.Clock.Now += TimeSpan.FromMinutes(6);
            else if (change == "ended") f.Clock.Now += TimeSpan.FromHours(2);
            else { var old = f.Store.Read(f.Command.MeetingId)!; f.Store.Replace(old, old with { State = "cancelled" }); }
        };
        var result = await f.Join();
        Assert.Equal(change == "cancelled" ? "schedule_authorization_denied" : "schedule_join_window_elapsed", result.FailureCode);
        Assert.Equal(0, f.Graph.Calls); Assert.Null(f.Calls.ReadJoin(f.Command.MeetingId));
    }

    [Fact]
    public async Task Unknown_Outlook_reference_and_wrong_meeting_binding_cannot_bypass_guard()
    {
        using var f = new Fixture();
        var unknown = f.Command with { MeetingId = Guid.NewGuid() };
        Assert.Equal("schedule_authorization_denied", (await f.Coordinator.JoinAsync(unknown, f.Client, default)).FailureCode);
        var wrong = f.Command with { CalendarEventId = "operator-cannot-bypass-scheduled-meeting" };
        Assert.Equal("schedule_authorization_denied", (await f.Coordinator.JoinAsync(wrong, f.Client, default)).FailureCode);
        Assert.Equal(0, f.Graph.Calls);
    }

    [Fact]
    public async Task Reload_preserves_actor_and_every_new_attempt_checks_current_grant()
    {
        using var f = new Fixture();
        var reloaded = new TeamsCalendarScheduleStore(Options.Create(f.Settings));
        Assert.Equal(f.Selection.Actor, reloaded.Read(f.Selection.MeetingId)!.Selection.Actor);
        f.Authorization.Result = ScheduleAuthorization.Unavailable;
        await f.Join(); await f.Join(); Assert.Equal(2, f.Authorization.Checks); Assert.Equal(0, f.Graph.Calls);
        Assert.False(f.Store.Add(f.Item with { State = "pending", Selection = f.Selection with {
            Actor = f.Selection.Actor! with { Subject = "another", AuthzPrincipal = "another" } } }));
    }

    [Fact]
    public async Task Legacy_actorless_snapshot_loads_but_never_authorizes_a_call()
    {
        using var f = new Fixture();
        using (var db = SnapshotTestStorage.Open(f.Settings.CalendarScheduleStateFilePath!))
        using (var update = db.CreateCommand()) {
            update.CommandText = "UPDATE snapshot SET payload=$payload WHERE slot=1";
            update.Parameters.AddWithValue("$payload", JsonSerializer.Serialize(new Dictionary<Guid, CalendarSchedule> {
                [f.Selection.MeetingId] = f.Item with { Selection = f.Selection with { Actor = null } } }));
            update.ExecuteNonQuery();
        }
        var reloaded = new TeamsCalendarScheduleStore(Options.Create(f.Settings));
        var guard = new TeamsScheduleDispatchGuard(reloaded, f.Authorization, Options.Create(f.Settings), f.Clock);
        await Assert.ThrowsAsync<TeamsScheduleNotAuthorizedException>(() => guard.ValidateAsync(f.Command, f.Meeting, default));
        Assert.Equal(0, f.Authorization.Checks);
    }

    [Fact]
    public async Task Service_authorization_uses_only_exact_audience_permission_and_stored_actor()
    {
        using var f = new Fixture();
        var handler = new AuthorizationHandler(HttpStatusCode.NoContent);
        var client = Authorizer(handler);
        Assert.Equal(ScheduleAuthorization.Allowed, await client.AuthorizeAsync(f.Selection, default));
        Assert.Equal(2, handler.Calls); Assert.Equal("http://auth-service:8088/oauth2/token", handler.Urls[0]);
        Assert.Contains("audience=meeting-service", handler.Bodies[0]);
        Assert.Contains("permissions=meeting%3Ateams-schedule%3Aauthorize", handler.Bodies[0]);
        Assert.Equal("Basic " + Convert.ToBase64String(Encoding.UTF8.GetBytes("teams-capture-worker:" + new string('x', 32))), handler.Headers[0]);
        Assert.Equal("Bearer synthetic-service-token", handler.Headers[1]);
        using var body = JsonDocument.Parse(handler.Bodies[1]);
        Assert.Equal(f.Selection.Actor!.Subject, body.RootElement.GetProperty("actor").GetProperty("subject").GetString());
        Assert.DoesNotContain("token", handler.Bodies[1]);
        Assert.EndsWith($"/{f.Selection.MeetingId}/teams-calendar/authorize", handler.Urls[1]);
    }

    [Theory]
    [InlineData(403, ScheduleAuthorization.Denied)]
    [InlineData(404, ScheduleAuthorization.Denied)]
    [InlineData(409, ScheduleAuthorization.Denied)]
    [InlineData(401, ScheduleAuthorization.Unavailable)]
    [InlineData(503, ScheduleAuthorization.Unavailable)]
    [InlineData(302, ScheduleAuthorization.Unavailable)]
    [InlineData(200, ScheduleAuthorization.Unavailable)]
    public async Task Only_explicit_204_is_allow(int code, ScheduleAuthorization expected)
    {
        using var f = new Fixture();
        Assert.Equal(expected, await Authorizer(new((HttpStatusCode)code)).AuthorizeAsync(f.Selection, default));
    }

    [Theory]
    [InlineData("broken")]
    [InlineData("oversized")]
    [InlineData("short-lived")]
    [InlineData("cancelled")]
    public async Task Bad_mint_response_never_reaches_authorization_endpoint(string fault)
    {
        using var f = new Fixture(); var handler = new AuthorizationHandler(HttpStatusCode.NoContent) { Fault = fault };
        Assert.Equal(ScheduleAuthorization.Unavailable, await Authorizer(handler).AuthorizeAsync(f.Selection, default));
        Assert.Equal(1, handler.Calls);
    }

    [Fact]
    public async Task Missing_service_secret_fails_closed_without_network()
    {
        using var f = new Fixture(); var handler = new AuthorizationHandler(HttpStatusCode.NoContent);
        var client = new HttpTeamsScheduleAuthorizer(Options.Create(new TeamsScheduleAuthorizationOptions { Enabled = true }), new HttpClient(handler));
        Assert.False(client.IsConfigured);
        Assert.Equal(ScheduleAuthorization.Unavailable, await client.AuthorizeAsync(f.Selection, default)); Assert.Equal(0, handler.Calls);
    }

    private static HttpTeamsScheduleAuthorizer Authorizer(AuthorizationHandler handler) => new(
        Options.Create(new TeamsScheduleAuthorizationOptions { Enabled = true, ClientSecret = new string('x', 32) }), new HttpClient(handler));

    private sealed class AuthorizationHandler(HttpStatusCode status) : HttpMessageHandler
    {
        public int Calls; public string? Fault;
        public readonly List<string> Urls = [], Bodies = [], Headers = [];
        protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken token) {
            Calls++; Urls.Add(request.RequestUri!.ToString()); Bodies.Add(await request.Content!.ReadAsStringAsync(token)); Headers.Add(request.Headers.Authorization!.ToString());
            Assert.False(request.Headers.Contains("X-Teams-Control-Key"));
            if (Calls > 1) return new(status);
            if (Fault == "cancelled") throw new OperationCanceledException();
            string body = Fault switch {
                "broken" => "{broken", "oversized" => new string('x', 65537),
                "short-lived" => "{\"access_token\":\"x\",\"token_type\":\"Bearer\",\"expires_in\":1}",
                _ => "{\"access_token\":\"synthetic-service-token\",\"token_type\":\"Bearer\",\"expires_in\":300}" };
            return new(HttpStatusCode.OK) { Content = new StringContent(body) };
        }
    }

    private sealed class Fixture : IDisposable
    {
        private readonly string directory = Path.Combine(Path.GetTempPath(), "teams-dispatch-" + Guid.NewGuid().ToString("N"));
        public readonly Clock Clock = new(); public readonly Authorization Authorization = new(); public readonly Token Token = new();
        public readonly Graph Graph = new(); public readonly TeamsCaptureOptions Settings;
        public readonly TeamsCalendarScheduleStore Store; public readonly TeamsCallbackState Calls;
        public readonly TeamsMeetingPresenceCoordinator Coordinator; public readonly GraphTeamsMeetingPresenceClient Client;
        public readonly CalendarSelection Selection; public readonly CalendarSchedule Item; public readonly MeetingPresenceCommand Command;
        public readonly ScheduledTeamsMeeting Meeting;
        public Fixture() {
            Directory.CreateDirectory(directory); var organizer = Guid.NewGuid(); var tenant = Guid.NewGuid();
            Settings = new() { Enabled = true, TenantId = tenant.ToString(), ApplicationId = Guid.NewGuid().ToString(), ClientSecret = "synthetic",
                ControlApiKey = new string('x', 32), PublicCallbackBaseUrl = "https://bot.test.example", CalendarSchedulingEnabled = true,
                CalendarOrganizerIds = [organizer], CallStateFilePath = Path.Combine(directory, "calls"), CalendarStateFilePath = Path.Combine(directory, "calendar"),
                CalendarScheduleStateFilePath = Path.Combine(directory, "schedules") };
            var options = Options.Create(Settings); Store = new(options); Calls = new(options);
            Selection = new(Guid.NewGuid(), organizer, "AAMk", "corr", new(1, "https://issuer.example", "subject", Guid.NewGuid(), tenant, "subject", 7, 35));
            var pending = new CalendarSchedule(Selection, "pending", Clock.Now, Clock.Now.AddHours(1), Clock.Now, Clock.Now);
            Assert.True(Store.Add(pending)); Item = pending with { State = "dispatching" }; Assert.True(Store.Replace(pending, Item));
            Command = new(Selection.MeetingId, Selection.Reference, Selection.CorrelationId); Meeting = new("19:test@thread.v2", "0", organizer.ToString());
            var guard = new TeamsScheduleDispatchGuard(Store, Authorization, options, Clock);
            Client = new(options, new Resolver(Meeting), Token, new HttpClient(Graph), guard);
            Coordinator = new(Calls, new TeamsOperationalTests.LifecycleStub());
        }
        public Task<MeetingPresenceResult> Join() => Coordinator.JoinAsync(Command, Client, default);
        public void Dispose() { if (Path.GetFullPath(directory).StartsWith(Path.GetFullPath(Path.GetTempPath()), StringComparison.OrdinalIgnoreCase)) Directory.Delete(directory, true); }
    }
    private sealed class Clock : TimeProvider { public DateTimeOffset Now = DateTimeOffset.Parse("2026-09-25T12:00:00Z"); public override DateTimeOffset GetUtcNow() => Now; }
    private sealed class Authorization : ITeamsScheduleAuthorizer {
        public bool IsConfigured => true; public int Checks; public ScheduleAuthorization Result = ScheduleAuthorization.Allowed; public Action? DuringCheck;
        public Task<ScheduleAuthorization> AuthorizeAsync(CalendarSelection selection, CancellationToken token) { Checks++; DuringCheck?.Invoke(); return Task.FromResult(Result); }
    }
    private sealed class Token : ITeamsAccessTokenProvider { public Action? BeforeReturn; public Task<string?> GetAccessTokenAsync(CancellationToken token) { BeforeReturn?.Invoke(); return Task.FromResult<string?>("synthetic-entra-token"); } }
    private sealed class Resolver(ScheduledTeamsMeeting meeting) : ITeamsCalendarMeetingResolver { public Task<ScheduledTeamsMeeting?> ResolveAsync(string id, CancellationToken token) => Task.FromResult<ScheduledTeamsMeeting?>(meeting); }
    private sealed class Graph : HttpMessageHandler {
        public int Calls; public bool Ambiguous;
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken token) {
            Calls++; Assert.Equal("https://graph.microsoft.com/v1.0/communications/calls", request.RequestUri!.ToString());
            if (Ambiguous) throw new HttpRequestException("synthetic post timeout");
            return Task.FromResult(new HttpResponseMessage(HttpStatusCode.Created) { Content = new StringContent("{\"id\":\"call-scheduled\"}") });
        }
    }
}
