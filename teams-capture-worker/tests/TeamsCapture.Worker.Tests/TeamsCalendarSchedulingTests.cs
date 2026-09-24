using System.Net;
using System.Net.Http.Json;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;
using Microsoft.Extensions.Hosting;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;
using TeamsCapture.Worker;
using Xunit;

namespace TeamsCapture.Worker.Tests;

public sealed class TeamsCalendarSchedulingTests
{
    [Fact]
    public async Task Scheduled_event_waits_until_start_then_joins_once_with_canonical_meeting_and_survives_reload()
    {
        using var app = new Factory();
        var id = await app.Select();
        await app.Scheduler.TickAsync(default);
        Assert.Equal(0, app.Presence.Count);
        Assert.Equal("pending", new TeamsCalendarScheduleStore(Options.Create(app.Settings)).Read(id)!.State);
        app.Clock.Now += TimeSpan.FromMinutes(1);
        app.Presence.BeforeJoin = command =>
        {
            Assert.Equal(id, command.MeetingId);
            Assert.StartsWith("outlook-", command.CalendarEventId);
            Assert.Contains("dispatching", SnapshotTestStorage.Read(app.Settings.CalendarScheduleStateFilePath!));
        };
        await app.Scheduler.TickAsync(default);
        await app.Scheduler.TickAsync(default);
        Assert.Equal(1, app.Presence.Count);
        Assert.Equal("joined", app.Store.Read(id)!.State);
        Assert.Equal(id, app.Services.GetRequiredService<TeamsCallbackState>().ReadMeetingId("call-scheduled"));
        Assert.Equal("joined", new TeamsCalendarScheduleStore(Options.Create(app.Settings)).Read(id)!.State);
    }

    [Fact]
    public async Task Outlook_time_change_is_observed_instead_of_joining_at_old_time()
    {
        using var app = new Factory();
        var id = await app.Select();
        app.Clock.Now += TimeSpan.FromMinutes(1);
        app.Calendar.Current = app.Calendar.Current! with { StartsAt = app.Clock.Now.AddHours(1), EndsAt = app.Clock.Now.AddHours(2) };
        await app.Scheduler.TickAsync(default);
        Assert.Equal(0, app.Presence.Count);
        Assert.Equal(app.Calendar.Current.StartsAt, app.Store.Read(id)!.StartsAt);
    }

    [Theory]
    [InlineData("cancelled", "cancelled")]
    [InlineData("expired", "expired")]
    [InlineData("unavailable", "pending")]
    public async Task Cancelled_late_or_unreadable_event_is_not_joined(string condition, string expectedState)
    {
        using var app = new Factory();
        var id = await app.Select();
        app.Clock.Now += TimeSpan.FromMinutes(10);
        app.Calendar.Current = condition switch {
            "cancelled" => app.Calendar.Current! with { Cancelled = true }, "unavailable" => null, _ => app.Calendar.Current };
        await app.Scheduler.TickAsync(default);
        Assert.Equal(expectedState, app.Store.Read(id)!.State);
        Assert.Equal(0, app.Presence.Count);
    }

    [Fact]
    public async Task Cancellation_during_lookup_wins_before_durable_dispatch_boundary()
    {
        using var app = new Factory();
        var id = await app.Select();
        app.Clock.Now += TimeSpan.FromMinutes(1);
        app.Calendar.DuringResolve = async () =>
        {
            using var client = app.AuthorizedClient();
            var response = await client.DeleteAsync($"/api/teams/meetings/{id}/calendar-schedule");
            Assert.Equal(HttpStatusCode.NoContent, response.StatusCode);
        };
        await app.Scheduler.TickAsync(default);
        Assert.Equal("cancelled", app.Store.Read(id)!.State);
        Assert.Equal(0, app.Presence.Count);
    }

    [Fact]
    public async Task Outlook_cancellation_during_online_meeting_lookup_is_rechecked()
    {
        using var app = new Factory();
        var id = await app.Select();
        app.Clock.Now += TimeSpan.FromMinutes(1);
        app.Calendar.DuringResolve = () => { app.Calendar.Current = app.Calendar.Current! with { Cancelled = true }; return Task.CompletedTask; };
        await app.Scheduler.TickAsync(default);
        Assert.Equal("cancelled", app.Store.Read(id)!.State);
        Assert.Equal(0, app.Presence.Count);
    }

    [Fact]
    public async Task Storage_failure_prevents_outgoing_join_and_leaves_pending_snapshot()
    {
        using var app = new Factory();
        var id = await app.Select();
        app.Clock.Now += TimeSpan.FromMinutes(1);
        SnapshotTestStorage.BlockWrites(app.Settings.CalendarScheduleStateFilePath!);
        await Assert.ThrowsAsync<IOException>(() => app.Scheduler.TickAsync(default));
        Assert.Equal(0, app.Presence.Count);
        Assert.Equal("pending", app.Store.Read(id)!.State);
    }

    [Fact]
    public async Task Ambiguous_remote_POST_is_never_automatically_resent()
    {
        using var app = new Factory();
        var id = await app.Select();
        app.Clock.Now += TimeSpan.FromMinutes(1);
        app.Presence.Ambiguous = true;
        await app.Scheduler.TickAsync(default);
        app.Clock.Now += TimeSpan.FromMinutes(1);
        await app.Scheduler.TickAsync(default);
        Assert.Equal(1, app.Presence.Count);
        Assert.Equal("join_outcome_unconfirmed", app.Store.Read(id)!.Failure);
        using var client = app.AuthorizedClient();
        var resubmit = await client.PostAsJsonAsync($"/api/teams/meetings/{id}/calendar-schedule", app.Request());
        Assert.Equal(HttpStatusCode.OK, resubmit.StatusCode);
        Assert.Equal("failed", app.Store.Read(id)!.State);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public async Task Interrupted_dispatch_reconciles_durable_receipt_without_creating_another_call(bool hasReceipt)
    {
        using var app = new Factory();
        var id = await app.Select();
        var item = app.Store.Read(id)!;
        app.Store.Replace(item, item with { State = "dispatching" });
        var calls = app.Services.GetRequiredService<TeamsCallbackState>();
        calls.ReserveJoin(id, item.Selection.Reference);
        if (hasReceipt) calls.CompleteJoin(id, "existing-call");
        await app.Scheduler.TickAsync(default);
        Assert.Equal(hasReceipt ? "joined" : "failed", app.Store.Read(id)!.State);
        Assert.Equal(0, app.Presence.Count);
    }

    [Theory]
    [InlineData(null)]
    [InlineData("incorrect-key")]
    public async Task Unauthorized_request_cannot_read_calendar_or_allocate_storage(string? key)
    {
        using var app = new Factory();
        using var client = app.CreateClient();
        if (key is not null) client.DefaultRequestHeaders.Add("X-Teams-Control-Key", key);
        var id = Guid.NewGuid();
        Assert.Equal(HttpStatusCode.Unauthorized, (await client.PostAsJsonAsync($"/api/teams/meetings/{id}/calendar-schedule", app.Request())).StatusCode);
        Assert.Equal(HttpStatusCode.Unauthorized, (await client.GetAsync($"/api/teams/meetings/{id}/calendar-schedule")).StatusCode);
        Assert.Equal(HttpStatusCode.Unauthorized, (await client.DeleteAsync($"/api/teams/meetings/{id}/calendar-schedule")).StatusCode);
        Assert.Equal(0, app.Calendar.Reads);
        Assert.Empty(Directory.GetFiles(app.DirectoryPath));
    }

    [Fact]
    public async Task Unlisted_organizer_and_invalid_selection_do_not_consume_capacity()
    {
        using var app = new Factory();
        using var client = app.AuthorizedClient();
        var path = $"/api/teams/meetings/{Guid.NewGuid()}/calendar-schedule";
        Assert.Equal(HttpStatusCode.Forbidden, (await client.PostAsJsonAsync(path, app.Request() with { OrganizerId = Guid.NewGuid() })).StatusCode);
        Assert.Equal(HttpStatusCode.BadRequest, (await client.PostAsJsonAsync(path, app.Request() with { EventId = "../path" })).StatusCode);
        Assert.Equal(0, app.Calendar.Reads);
        Assert.Empty(Directory.GetFiles(app.DirectoryPath));
    }

    [Fact]
    public async Task Same_Outlook_event_cannot_be_bound_to_two_canonical_meetings()
    {
        using var app = new Factory();
        await app.Select();
        using var client = app.AuthorizedClient();
        var result = await client.PostAsJsonAsync($"/api/teams/meetings/{Guid.NewGuid()}/calendar-schedule", app.Request());
        Assert.Equal(HttpStatusCode.Conflict, result.StatusCode);
    }

    private sealed class Factory : WebApplicationFactory<Program>
    {
        public string DirectoryPath { get; } = Path.Combine(Path.GetTempPath(), "teams-schedule-" + Guid.NewGuid().ToString("N"));
        public readonly Clock Clock = new();
        public readonly Presence Presence = new();
        public readonly Calendar Calendar;
        public readonly TeamsCaptureOptions Settings;
        public TeamsCalendarScheduleStore Store => Services.GetRequiredService<TeamsCalendarScheduleStore>();
        public TeamsCalendarSchedulingService Scheduler => Services.GetRequiredService<TeamsCalendarSchedulingService>();
        public Factory()
        {
            Calendar = new(Clock.Now);
            Settings = new() { Enabled = true, TenantId = Guid.NewGuid().ToString(), ApplicationId = Guid.NewGuid().ToString(),
                ClientSecret = "synthetic", ControlApiKey = new string('x', 32), PublicCallbackBaseUrl = "https://bot.test.example",
                CallStateFilePath = Path.Combine(DirectoryPath, "calls"), CalendarStateFilePath = Path.Combine(DirectoryPath, "calendar"),
                CalendarSchedulingEnabled = true, CalendarOrganizerIds = [Guid.NewGuid()],
                CalendarScheduleStateFilePath = Path.Combine(DirectoryPath, "schedules") };
        }
        public CalendarScheduleRequest Request() => new(Settings.CalendarOrganizerIds[0], "AAMk+/=", "corr-test");
        public HttpClient AuthorizedClient() { var client = CreateClient(); client.DefaultRequestHeaders.Add("X-Teams-Control-Key", Settings.ControlApiKey); return client; }
        public async Task<Guid> Select()
        {
            var id = Guid.NewGuid();
            using var client = AuthorizedClient();
            Assert.Equal(HttpStatusCode.Accepted, (await client.PostAsJsonAsync($"/api/teams/meetings/{id}/calendar-schedule", Request())).StatusCode);
            return id;
        }
        protected override void ConfigureWebHost(IWebHostBuilder builder)
        {
            Directory.CreateDirectory(DirectoryPath);
            builder.UseContentRoot(AppContext.BaseDirectory);
            builder.ConfigureLogging(logging => logging.ClearProviders());
            builder.ConfigureServices(services => {
                services.RemoveAll<IHostedService>();
                services.RemoveAll<IOptions<TeamsCaptureOptions>>(); services.AddSingleton(Options.Create(Settings));
                services.RemoveAll<TimeProvider>(); services.AddSingleton<TimeProvider>(Clock);
                services.RemoveAll<ITeamsCalendarClient>(); services.AddSingleton<ITeamsCalendarClient>(Calendar);
                services.RemoveAll<ITeamsMeetingPresenceClient>(); services.AddSingleton<ITeamsMeetingPresenceClient>(Presence);
                services.AddSingleton<TeamsCalendarSchedulingService>();
            });
        }
        protected override void Dispose(bool disposing)
        {
            base.Dispose(disposing);
            if (Directory.Exists(DirectoryPath) && Path.GetFullPath(DirectoryPath).StartsWith(Path.GetFullPath(Path.GetTempPath()), StringComparison.OrdinalIgnoreCase))
                Directory.Delete(DirectoryPath, true);
        }
    }
    private sealed class Clock : TimeProvider
    {
        public DateTimeOffset Now = DateTimeOffset.Parse("2026-09-24T12:00:00Z");
        public override DateTimeOffset GetUtcNow() => Now;
    }
    private sealed class Calendar(DateTimeOffset now) : ITeamsCalendarClient
    {
        public TeamsCalendarEvent? Current = new(now.AddMinutes(1), now.AddHours(1), false, "https://teams.microsoft.com/meet/123");
        public int Reads;
        public Func<Task>? DuringResolve;
        public Task<TeamsCalendarEvent?> ReadAsync(Guid organizerId, string eventId, CancellationToken cancellationToken)
        { Reads++; return Task.FromResult(Current); }
        public async Task<ScheduledTeamsMeeting?> ResolveMeetingAsync(Guid organizerId, string joinUrl, CancellationToken cancellationToken)
        { if (DuringResolve is not null) await DuringResolve(); return new("19:test@thread.v2", "0", organizerId.ToString()); }
    }
    private sealed class Presence : ITeamsMeetingPresenceClient
    {
        public int Count;
        public bool Ambiguous;
        public Action<MeetingPresenceCommand>? BeforeJoin;
        public Task<TeamsJoinReceipt?> JoinAsync(MeetingPresenceCommand command, CancellationToken cancellationToken)
        {
            Count++; BeforeJoin?.Invoke(command);
            if (Ambiguous) throw new HttpRequestException("synthetic remote timeout");
            return Task.FromResult<TeamsJoinReceipt?>(new("call-scheduled"));
        }
    }
}
