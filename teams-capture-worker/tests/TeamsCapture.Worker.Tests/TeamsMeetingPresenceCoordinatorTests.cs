using TeamsCapture.Worker;
using Xunit;

namespace TeamsCapture.Worker.Tests;

public sealed class TeamsMeetingPresenceCoordinatorTests
{
    [Fact]
    public async Task Requires_the_canonical_meeting_and_calendar_reference()
    {
        var result = await new TeamsMeetingPresenceCoordinator().JoinAsync(
            new MeetingPresenceCommand(Guid.Empty, "calendar-event", "corr-1"),
            new FakeTeamsClient(new TeamsJoinReceipt("call-1")),
            CancellationToken.None);

        Assert.False(result.Joined);
        Assert.Equal("invalid_meeting_reference", result.FailureCode);
    }

    [Fact]
    public async Task Returns_only_the_Teams_call_receipt()
    {
        var result = await new TeamsMeetingPresenceCoordinator().JoinAsync(
            new MeetingPresenceCommand(Guid.NewGuid(), "calendar-event", "corr-1"),
            new FakeTeamsClient(new TeamsJoinReceipt("call-1")),
            CancellationToken.None);

        Assert.True(result.Joined);
        Assert.Equal("call-1", result.CallId);
    }

    private sealed class FakeTeamsClient(TeamsJoinReceipt? receipt) : ITeamsMeetingPresenceClient
    {
        public Task<TeamsJoinReceipt?> JoinAsync(MeetingPresenceCommand command, CancellationToken cancellationToken) =>
            Task.FromResult(receipt);
    }
}
