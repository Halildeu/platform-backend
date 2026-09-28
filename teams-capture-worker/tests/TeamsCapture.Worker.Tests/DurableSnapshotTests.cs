using System.Text.Json;
using TeamsCapture.Worker;
using Xunit;

namespace TeamsCapture.Worker.Tests;

public sealed class DurableSnapshotTests
{
    [Fact]
    public async Task Reservation_is_committed_and_readable_before_remote_join_is_dispatched()
    {
        using var fixture = new TeamsOperationalTests.Fixture();
        var command = new MeetingPresenceCommand(Guid.NewGuid(), "event-1", "corr-1");
        var client = new ObserveJoin(() =>
        {
            var restarted = new TeamsCallbackState(fixture.Options);
            Assert.Equal("event-1", restarted.ReadJoin(command.MeetingId)?.CalendarEventId);
            Assert.False(restarted.ReserveJoin(command.MeetingId, "event-1").Reserved);
        });
        var coordinator = new TeamsMeetingPresenceCoordinator(new(fixture.Options), new NoCleanup());
        Assert.True((await coordinator.JoinAsync(command, client, CancellationToken.None)).Joined);
        Assert.Equal("call-1", new TeamsCallbackState(fixture.Options).ReadJoin(command.MeetingId)?.CallId);
    }

    [Fact]
    public void Uncommitted_database_change_does_not_remove_prior_reservation()
    {
        using var fixture = new TeamsOperationalTests.Fixture();
        var meetingId = Guid.NewGuid();
        var state = new TeamsCallbackState(fixture.Options);
        state.ReserveJoin(meetingId, "event-1");
        using (var connection = SnapshotTestStorage.Open(fixture.Options.Value.CallStateFilePath!))
        {
            using var transaction = connection.BeginTransaction();
            using var command = connection.CreateCommand();
            command.Transaction = transaction;
            command.CommandText = "UPDATE snapshot SET payload = '{\"Calls\":{},\"Joins\":{}}'";
            command.ExecuteNonQuery();
            // Leave without commit; reopening must still see the reservation.
        }
        var restarted = new TeamsCallbackState(fixture.Options);
        Assert.Equal("event-1", restarted.ReadJoin(meetingId)?.CalendarEventId);
        Assert.False(restarted.ReserveJoin(meetingId, "event-1").Reserved);
    }

    [Fact]
    public void Legacy_json_is_read_once_and_cannot_resurrect_expired_calls()
    {
        using var fixture = new TeamsOperationalTests.Fixture();
        var path = fixture.Options.Value.CallStateFilePath!;
        var meetingId = Guid.NewGuid();
        var json = JsonSerializer.Serialize(new Dictionary<string, object>
        {
            ["legacy-call"] = new { MeetingId = meetingId, State = "terminated", UpdatedAt = DateTimeOffset.UtcNow.AddDays(-3) }
        });
        File.WriteAllText(path, json);
        var state = new TeamsCallbackState(fixture.Options);
        Assert.Equal(meetingId, state.ReadMeetingId("legacy-call"));
        state.RemoveExpiredCompleted(DateTimeOffset.UtcNow.AddDays(-1));
        Assert.Equal(json, File.ReadAllText(path));
        Assert.Null(new TeamsCallbackState(fixture.Options).Read("legacy-call"));
    }

    [Fact]
    public void Existing_broken_database_never_falls_back_to_stale_json()
    {
        using var fixture = new TeamsOperationalTests.Fixture();
        var path = fixture.Options.Value.CallStateFilePath!;
        File.WriteAllText(path, "{}");
        File.WriteAllText(path + ".sqlite3", "not a database");
        Assert.ThrowsAny<IOException>(() => new TeamsCallbackState(fixture.Options));
    }

    [Fact]
    public void Missing_snapshot_row_is_not_treated_as_an_empty_store()
    {
        using var fixture = new TeamsOperationalTests.Fixture();
        var state = new TeamsCallbackState(fixture.Options);
        state.ReserveJoin(Guid.NewGuid(), "event-1");
        using (var connection = SnapshotTestStorage.Open(fixture.Options.Value.CallStateFilePath!))
        {
            using var command = connection.CreateCommand();
            command.CommandText = "DELETE FROM snapshot";
            command.ExecuteNonQuery();
        }
        Assert.Throws<InvalidDataException>(() => new TeamsCallbackState(fixture.Options));
        Assert.Throws<InvalidDataException>(() => state.ReserveJoin(Guid.NewGuid(), "event-2"));
    }

    private sealed class ObserveJoin(Action observe) : ITeamsMeetingPresenceClient
    {
        public Task<TeamsJoinReceipt?> JoinAsync(MeetingPresenceCommand command, CancellationToken cancellationToken)
        {
            observe();
            return Task.FromResult<TeamsJoinReceipt?>(new("call-1"));
        }
    }

    private sealed class NoCleanup : ITeamsCallLifecycleClient
    {
        public Task<TeamsCallOperationResult> KeepAliveAsync(string callId, CancellationToken cancellationToken) =>
            throw new InvalidOperationException("Unexpected heartbeat");
        public Task<TeamsCallOperationResult> LeaveAsync(string callId, CancellationToken cancellationToken) =>
            throw new InvalidOperationException("Unexpected cleanup");
    }
}
