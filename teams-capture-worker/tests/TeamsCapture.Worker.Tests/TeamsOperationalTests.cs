using System.Text.Json;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;
using TeamsCapture.Worker;
using Xunit;

namespace TeamsCapture.Worker.Tests;

public sealed class TeamsOperationalTests
{
    [Fact]
    public async Task Concurrent_joins_and_retry_after_restart_create_one_call()
    {
        using var fixture = new Fixture();
        var state = new TeamsCallbackState(fixture.Options);
        var client = new JoinStub();
        var entered = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var release = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        client.OnJoin = async () => { entered.SetResult(); await release.Task; };
        var coordinator = new TeamsMeetingPresenceCoordinator(state, new LifecycleStub());
        var command = Command();
        var first = coordinator.JoinAsync(command, client, CancellationToken.None);
        await entered.Task;
        var duplicate = await coordinator.JoinAsync(command, client, CancellationToken.None);
        Assert.Equal("join_outcome_unconfirmed", duplicate.FailureCode);
        release.SetResult();
        Assert.True((await first).Joined);
        var restored = new TeamsCallbackState(fixture.Options);
        var retry = await new TeamsMeetingPresenceCoordinator(restored, new LifecycleStub())
            .JoinAsync(command, client, CancellationToken.None);
        Assert.Equal("call-1", retry.CallId);
        Assert.Equal(1, client.Requests);
    }

    [Fact]
    public async Task Ambiguous_post_survives_restart_and_is_not_sent_again()
    {
        using var fixture = new Fixture();
        var command = Command();
        var client = new JoinStub { OnJoin = () => throw new HttpRequestException("synthetic timeout") };
        var state = new TeamsCallbackState(fixture.Options);
        var coordinator = new TeamsMeetingPresenceCoordinator(state, new LifecycleStub());
        Assert.False((await coordinator.JoinAsync(command, client, CancellationToken.None)).Joined);
        var restarted = new TeamsMeetingPresenceCoordinator(new TeamsCallbackState(fixture.Options), new LifecycleStub());
        Assert.Equal("join_outcome_unconfirmed", (await restarted.JoinAsync(command, client, CancellationToken.None)).FailureCode);
        Assert.Equal(1, client.Requests);
    }

    [Fact]
    public async Task Persistence_failure_before_post_never_contacts_Graph()
    {
        using var fixture = new Fixture();
        var state = new TeamsCallbackState(fixture.Options);
        Directory.CreateDirectory(fixture.Options.Value.CallStateFilePath! + ".tmp");
        var client = new JoinStub();
        await Assert.ThrowsAnyAsync<Exception>(() => new TeamsMeetingPresenceCoordinator(state, new LifecycleStub())
            .JoinAsync(Command(), client, CancellationToken.None));
        Assert.Equal(0, client.Requests);
    }

    [Fact]
    public async Task Verified_not_created_outcome_allows_retry_after_restart()
    {
        using var fixture = new Fixture();
        var command = Command();
        var client = new JoinStub { OnJoin = () => throw new TeamsJoinNotCreatedException() };
        var state = new TeamsCallbackState(fixture.Options);
        Assert.Equal("join_not_created", (await new TeamsMeetingPresenceCoordinator(state, new LifecycleStub())
            .JoinAsync(command, client, CancellationToken.None)).FailureCode);
        Assert.Null(state.ReadJoin(command.MeetingId));
        client.OnJoin = () => Task.CompletedTask;
        var restored = new TeamsCallbackState(fixture.Options);
        Assert.True((await new TeamsMeetingPresenceCoordinator(restored, new LifecycleStub())
            .JoinAsync(command, client, CancellationToken.None)).Joined);
        Assert.Equal(2, client.Requests);
    }

    [Fact]
    public async Task Already_cancelled_request_leaves_no_reservation()
    {
        var state = new TeamsCallbackState();
        var client = new JoinStub();
        var command = Command();
        Assert.Equal("join_not_sent", (await new TeamsMeetingPresenceCoordinator(state, new LifecycleStub())
            .JoinAsync(command, client, new CancellationToken(true))).FailureCode);
        Assert.Null(state.ReadJoin(command.MeetingId));
        Assert.Equal(0, client.Requests);
    }

    [Theory]
    [InlineData(TeamsCallOperationResult.Succeeded, "join_persistence_failed_call_removed")]
    [InlineData(TeamsCallOperationResult.Unconfirmed, "join_persistence_failed_cleanup_unconfirmed")]
    public async Task Failed_commit_attempts_bounded_compensation_and_never_reposts(TeamsCallOperationResult cleanup, string failure)
    {
        using var fixture = new Fixture();
        var state = new TeamsCallbackState(fixture.Options);
        var command = Command();
        var client = new JoinStub { OnJoin = () =>
        {
            Directory.CreateDirectory(fixture.Options.Value.CallStateFilePath! + ".tmp");
            return Task.CompletedTask;
        }};
        var lifecycle = new LifecycleStub { LeaveResult = cleanup };
        var result = await new TeamsMeetingPresenceCoordinator(state, lifecycle).JoinAsync(command, client, CancellationToken.None);
        Assert.Equal(failure, result.FailureCode);
        Assert.Equal(1, lifecycle.Leaves);
        Assert.Null(state.Read("call-1"));
        var restored = new TeamsCallbackState(fixture.Options);
        Assert.False((await new TeamsMeetingPresenceCoordinator(restored, lifecycle)
            .JoinAsync(command, client, CancellationToken.None)).Joined);
        Assert.Equal(1, client.Requests);
    }

    [Fact]
    public async Task Full_capacity_rejects_before_remote_creation()
    {
        var state = new TeamsCallbackState();
        for (var i = 0; i < 1000; i++) Assert.True(state.Register($"call-{i}", Guid.NewGuid()));
        var client = new JoinStub();
        var result = await new TeamsMeetingPresenceCoordinator(state, new LifecycleStub()).JoinAsync(Command(), client, CancellationToken.None);
        Assert.Equal("capacity_exhausted", result.FailureCode);
        Assert.Equal(0, client.Requests);
    }

    [Fact]
    public async Task A_different_calendar_or_terminal_call_cannot_reopen_the_meeting()
    {
        var state = new TeamsCallbackState();
        var command = Command();
        var client = new JoinStub();
        var coordinator = new TeamsMeetingPresenceCoordinator(state, new LifecycleStub());
        Assert.True((await coordinator.JoinAsync(command, client, CancellationToken.None)).Joined);
        Assert.Equal("conflict", (await coordinator.JoinAsync(command with { CalendarEventId = "another-event" }, client, CancellationToken.None)).FailureCode);
        state.MarkTerminated("call-1");
        Assert.Equal("call_already_ended", (await coordinator.JoinAsync(command, client, CancellationToken.None)).FailureCode);
        Assert.Equal(1, client.Requests);
    }

    [Fact]
    public async Task Conflicting_call_receipt_cannot_remove_another_meetings_call()
    {
        var state = new TeamsCallbackState();
        var owner = Guid.NewGuid();
        state.Register("call-1", owner);
        var lifecycle = new LifecycleStub();
        var result = await new TeamsMeetingPresenceCoordinator(state, lifecycle).JoinAsync(Command(), new JoinStub(), CancellationToken.None);
        Assert.Equal("join_receipt_ownership_conflict", result.FailureCode);
        Assert.Equal(owner, state.ReadMeetingId("call-1"));
        Assert.Equal(0, lifecycle.Leaves);
    }

    [Theory]
    [InlineData(false)]
    [InlineData(true)]
    public void Roster_notifications_do_not_block_termination_or_persist_names(bool mixed)
    {
        using var fixture = new Fixture();
        var state = new TeamsCallbackState(fixture.Options);
        state.Register("call-1", Guid.NewGuid());
        var updates = new List<object>
        {
            new { resourceUrl = "/communications/calls/call-1/participants", resourceData = new[] { new { id = "p1", displayName = "PRIVATE-NAME" } } }
        };
        if (mixed) updates.Add(new { resourceUrl = "/communications/calls/call-1", resourceData = new { state = "terminated" } });
        Assert.True(state.Apply(JsonSerializer.SerializeToElement(new { value = updates })));
        Assert.Equal(mixed ? "terminated" : "establishing", state.Read("call-1"));
        Assert.DoesNotContain("PRIVATE-NAME", File.ReadAllText(fixture.Options.Value.CallStateFilePath!));
    }

    [Theory]
    [InlineData("unknown/participants", "[]")]
    [InlineData("call-1/participants/more", "[]")]
    [InlineData("call-1/participants", "{}")]
    [InlineData("call-1/participants", "[null]")]
    public void Roster_cannot_ack_unknown_calls_or_malformed_resources(string resource, string data)
    {
        var state = new TeamsCallbackState();
        state.Register("call-1", Guid.NewGuid());
        using var json = JsonDocument.Parse("{\"value\":[{\"resourceUrl\":\"/communications/calls/" + resource + "\",\"resourceData\":" + data + "}]}");
        Assert.False(state.Apply(json.RootElement));
    }

    [Fact]
    public async Task Heartbeat_repeats_at_fifteen_minutes_stops_on_404_and_does_not_reopen_calls()
    {
        using var fixture = new Fixture();
        var state = new TeamsCallbackState();
        state.Register("call-1", Guid.NewGuid());
        var lifecycle = new LifecycleStub();
        var clock = new ManualClock();
        var service = new TeamsCallMaintenanceService(state, new(fixture.Options), lifecycle, fixture.Options, clock,
            NullLogger<TeamsCallMaintenanceService>.Instance);
        await service.MaintainOnceAsync(CancellationToken.None);
        Assert.Equal(1, lifecycle.Heartbeats);
        clock.Advance(TimeSpan.FromMinutes(14));
        await service.MaintainOnceAsync(CancellationToken.None);
        Assert.Equal(1, lifecycle.Heartbeats);
        clock.Advance(TimeSpan.FromMinutes(1));
        await service.MaintainOnceAsync(CancellationToken.None);
        Assert.Equal(2, lifecycle.Heartbeats);
        lifecycle.KeepAliveResult = TeamsCallOperationResult.Ended;
        clock.Advance(TimeSpan.FromMinutes(15));
        await service.MaintainOnceAsync(CancellationToken.None);
        Assert.Equal("terminated", state.Read("call-1"));
        clock.Advance(TimeSpan.FromMinutes(30));
        await service.MaintainOnceAsync(CancellationToken.None);
        Assert.Equal(3, lifecycle.Heartbeats);
    }

    [Fact]
    public async Task Unconfirmed_heartbeat_retries_in_one_minute_without_claiming_termination()
    {
        using var fixture = new Fixture();
        var state = new TeamsCallbackState();
        state.Register("call-1", Guid.NewGuid());
        var lifecycle = new LifecycleStub { KeepAliveResult = TeamsCallOperationResult.Unconfirmed };
        var clock = new ManualClock();
        var service = new TeamsCallMaintenanceService(state, new(fixture.Options), lifecycle, fixture.Options, clock,
            NullLogger<TeamsCallMaintenanceService>.Instance);
        await service.MaintainOnceAsync(CancellationToken.None);
        clock.Advance(TimeSpan.FromMinutes(1));
        await service.MaintainOnceAsync(CancellationToken.None);
        Assert.Equal(2, lifecycle.Heartbeats);
        Assert.Equal("establishing", state.Read("call-1"));
    }

    [Fact]
    public async Task Configured_retention_cleans_completed_call_calendar_and_reservation_but_keeps_active()
    {
        using var fixture = new Fixture(retention: 24);
        var state = new TeamsCallbackState(fixture.Options);
        var calendar = new DurableTeamsCalendarMeetingResolver(fixture.Options);
        var completed = Guid.NewGuid();
        calendar.Register(completed, "event-1", new("thread", "0", Guid.NewGuid().ToString()));
        state.ReserveJoin(completed, "event-1");
        state.CompleteJoin(completed, "call-1");
        state.MarkTerminated("call-1");
        state.Register("call-active", Guid.NewGuid());
        var clock = new ManualClock();
        clock.Advance(TimeSpan.FromDays(2));
        var service = new TeamsCallMaintenanceService(state, calendar, new LifecycleStub(), fixture.Options, clock,
            NullLogger<TeamsCallMaintenanceService>.Instance);
        await service.MaintainOnceAsync(CancellationToken.None);
        Assert.Null(state.Read("call-1"));
        Assert.Null(state.ReadJoin(completed));
        Assert.Null(await calendar.ResolveAsync("event-1", CancellationToken.None));
        var restored = new TeamsCallbackState(fixture.Options);
        Assert.Null(restored.Read("call-1"));
        Assert.NotNull(restored.Read("call-active"));
    }

    [Fact]
    public void Reusing_a_call_id_for_another_meeting_is_rejected()
    {
        var state = new TeamsCallbackState();
        Assert.True(state.Register("call-1", Guid.NewGuid()));
        Assert.False(state.Register("call-1", Guid.NewGuid()));
        Assert.False(state.Register("../call", Guid.NewGuid()));
    }

    [Fact]
    public async Task Retention_write_failure_does_not_starve_active_heartbeats()
    {
        using var fixture = new Fixture(retention: 24);
        var state = new TeamsCallbackState(fixture.Options);
        var calendar = new DurableTeamsCalendarMeetingResolver(fixture.Options);
        var completed = Guid.NewGuid();
        calendar.Register(completed, "expired-event", new("thread", "0", Guid.NewGuid().ToString()));
        state.Register("expired", completed);
        state.MarkTerminated("expired");
        state.Register("active", Guid.NewGuid());
        Directory.CreateDirectory(fixture.Options.Value.CalendarStateFilePath! + ".tmp");
        var clock = new ManualClock();
        clock.Advance(TimeSpan.FromDays(2));
        var lifecycle = new LifecycleStub();
        await new TeamsCallMaintenanceService(state, calendar, lifecycle, fixture.Options, clock,
            NullLogger<TeamsCallMaintenanceService>.Instance).MaintainOnceAsync(CancellationToken.None);
        Assert.Equal(1, lifecycle.Heartbeats);
        Assert.NotNull(state.Read("expired"));
    }

    [Fact]
    public void Legacy_meeting_with_a_recent_terminal_call_is_retained_until_all_calls_expire()
    {
        using var fixture = new Fixture();
        var now = DateTimeOffset.UtcNow;
        var meetingId = Guid.NewGuid();
        var legacy = new Dictionary<string, object>
        {
            ["old"] = new { MeetingId = meetingId, State = "terminated", UpdatedAt = now.AddDays(-3) },
            ["recent"] = new { MeetingId = meetingId, State = "terminated", UpdatedAt = now }
        };
        File.WriteAllText(fixture.Options.Value.CallStateFilePath!, JsonSerializer.Serialize(legacy));
        var state = new TeamsCallbackState(fixture.Options);
        state.RemoveExpiredCompleted(now.AddDays(-1));
        Assert.NotNull(state.Read("old"));
        Assert.NotNull(state.Read("recent"));
        state.RemoveExpiredCompleted(now.AddDays(1));
        Assert.Null(state.Read("old"));
        Assert.Null(state.Read("recent"));
    }

    private static MeetingPresenceCommand Command() => new(Guid.NewGuid(), "event-1", "corr-1");

    internal sealed class Fixture : IDisposable
    {
        public string DirectoryPath { get; } = Path.Combine(Path.GetTempPath(), "teams-test-" + Guid.NewGuid().ToString("N"));
        public IOptions<TeamsCaptureOptions> Options { get; }
        public Fixture(int? retention = null)
        {
            Directory.CreateDirectory(DirectoryPath);
            Options = Microsoft.Extensions.Options.Options.Create(new TeamsCaptureOptions
            {
                Enabled = true, TenantId = Guid.NewGuid().ToString(), ApplicationId = Guid.NewGuid().ToString(),
                ClientSecret = "synthetic-test-credential", ControlApiKey = new string('k', 32),
                PublicCallbackBaseUrl = "https://bot.test.example", CallStateFilePath = Path.Combine(DirectoryPath, "calls.json"),
                CalendarStateFilePath = Path.Combine(DirectoryPath, "calendar.json"), CompletedCallRetentionHours = retention
            });
        }
        public void Dispose() { if (Directory.Exists(DirectoryPath)) Directory.Delete(DirectoryPath, true); }
    }

    private sealed class JoinStub : ITeamsMeetingPresenceClient
    {
        public int Requests { get; private set; }
        public Func<Task> OnJoin { get; set; } = () => Task.CompletedTask;
        public async Task<TeamsJoinReceipt?> JoinAsync(MeetingPresenceCommand command, CancellationToken cancellationToken)
        {
            Requests++;
            await OnJoin();
            return new("call-1");
        }
    }

    internal sealed class LifecycleStub : ITeamsCallLifecycleClient
    {
        public int Leaves { get; private set; }
        public int Heartbeats { get; private set; }
        public TeamsCallOperationResult LeaveResult { get; set; } = TeamsCallOperationResult.Succeeded;
        public TeamsCallOperationResult KeepAliveResult { get; set; } = TeamsCallOperationResult.Succeeded;
        public Task<TeamsCallOperationResult> LeaveAsync(string callId, CancellationToken cancellationToken)
        { Leaves++; return Task.FromResult(LeaveResult); }
        public Task<TeamsCallOperationResult> KeepAliveAsync(string callId, CancellationToken cancellationToken)
        { Heartbeats++; return Task.FromResult(KeepAliveResult); }
    }

    private sealed class ManualClock : TimeProvider
    {
        private DateTimeOffset now = DateTimeOffset.UtcNow;
        public override DateTimeOffset GetUtcNow() => now;
        public void Advance(TimeSpan elapsed) => now += elapsed;
    }
}
