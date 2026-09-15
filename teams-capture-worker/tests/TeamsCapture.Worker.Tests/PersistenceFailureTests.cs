using System.Text.Json;
using Microsoft.Extensions.Options;
using TeamsCapture.Worker;
using Xunit;

namespace TeamsCapture.Worker.Tests;

public sealed class PersistenceFailureTests
{
    [Fact]
    public async Task Calendar_retry_after_failed_write_is_persisted()
    {
        var root = Path.Combine(Path.GetTempPath(), Guid.NewGuid().ToString("N"));
        File.WriteAllText(root, "block directory creation");
        try
        {
            var options = Options.Create(new TeamsCaptureOptions { CalendarStateFilePath = Path.Combine(root, "calendar.json") });
            var state = new DurableTeamsCalendarMeetingResolver(options);
            var meeting = new ScheduledTeamsMeeting("19:meeting@thread.v2", "0", Guid.NewGuid().ToString());
            var id = Guid.NewGuid();
            Assert.ThrowsAny<IOException>(() => state.Register(id, "event-1", meeting));
            Assert.Null(await state.ResolveAsync("event-1", CancellationToken.None));
            File.Delete(root);
            Assert.True(state.Register(id, "event-1", meeting));
            Assert.Equal(meeting, await new DurableTeamsCalendarMeetingResolver(options).ResolveAsync("event-1", CancellationToken.None));
        }
        finally { Cleanup(root); }
    }

    [Fact]
    public void Call_retry_after_failed_write_is_persisted()
    {
        var root = Path.Combine(Path.GetTempPath(), Guid.NewGuid().ToString("N"));
        File.WriteAllText(root, "block directory creation");
        try
        {
            var options = Options.Create(new TeamsCaptureOptions { CallStateFilePath = Path.Combine(root, "calls.json") });
            var state = new TeamsCallbackState(options);
            var id = Guid.NewGuid();
            Assert.ThrowsAny<IOException>(() => state.Register("call-1", id));
            Assert.Null(state.Read("call-1"));
            Assert.Null(state.ReadMeetingId("call-1"));
            File.Delete(root);
            Assert.True(state.Register("call-1", id));
            Assert.Equal(id, new TeamsCallbackState(options).ReadMeetingId("call-1"));
        }
        finally { Cleanup(root); }
    }

    [Fact]
    public void Failed_callback_snapshot_does_not_publish_terminal_state()
    {
        var root = Path.Combine(Path.GetTempPath(), Guid.NewGuid().ToString("N"));
        var path = Path.Combine(root, "calls.json");
        try
        {
            var options = Options.Create(new TeamsCaptureOptions { CallStateFilePath = path });
            var state = new TeamsCallbackState(options);
            Assert.True(state.Register("call-1", Guid.NewGuid()));
            // Block the temporary write while preserving the previous durable snapshot.
            Directory.CreateDirectory(path + ".tmp");
            var payload = JsonSerializer.SerializeToElement(new { value = new[] {
                new { resourceUrl = "/communications/calls/call-1", resourceData = new { state = "terminated" } }
            } });
            Assert.ThrowsAny<Exception>(() => state.Apply(payload));
            Assert.Equal("establishing", state.Read("call-1"));
            Assert.Equal("establishing", new TeamsCallbackState(options).Read("call-1"));
            Directory.Delete(path + ".tmp");
            Assert.True(state.Apply(payload));
            Assert.Equal("terminated", new TeamsCallbackState(options).Read("call-1"));
        }
        finally { Cleanup(root); }
    }

    private static void Cleanup(string root)
    {
        if (File.Exists(root)) File.Delete(root);
        if (Directory.Exists(root)) Directory.Delete(root, true);
    }
}
