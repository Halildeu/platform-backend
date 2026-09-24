using Microsoft.Extensions.Options;
using TeamsCapture.Worker;
using Xunit;

namespace TeamsCapture.Worker.Tests;

public sealed class DurableTeamsCalendarMeetingResolverTests
{
    [Fact]
    public async Task Calendar_reference_survives_restart_and_cannot_be_rebound()
    {
        var directory = Path.Combine(Path.GetTempPath(), Guid.NewGuid().ToString("N"));
        var path = Path.Combine(directory, "calendar.json");
        var options = Options.Create(new TeamsCaptureOptions { CalendarStateFilePath = path });
        var meeting = new ScheduledTeamsMeeting("19:meeting@thread.v2", "0", Guid.NewGuid().ToString());
        var meetingId = Guid.NewGuid();
        try
        {
            Directory.CreateDirectory(directory);
            var first = new DurableTeamsCalendarMeetingResolver(options);
            Assert.True(first.Register(meetingId, "event-1", meeting));
            Assert.False(first.Register(meetingId, "event-1", meeting with { MessageId = "different" }));
            Assert.False(first.Register(Guid.NewGuid(), "event-1", meeting));

            var restored = new DurableTeamsCalendarMeetingResolver(options);
            Assert.Equal(meeting, await restored.ResolveAsync("event-1", CancellationToken.None));
        }
        finally
        {
            if (Directory.Exists(directory)) Directory.Delete(directory, true);
        }
    }
}
