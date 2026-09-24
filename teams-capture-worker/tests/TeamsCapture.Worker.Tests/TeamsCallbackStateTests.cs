using System.Text.Json;
using Microsoft.Extensions.Options;
using TeamsCapture.Worker;
using Xunit;

namespace TeamsCapture.Worker.Tests;

public class TeamsCallbackStateTests
{
    [Fact]
    public void Unknown_calls_are_not_acknowledged()
    {
        var state = new TeamsCallbackState();
        Assert.False(state.Apply(Event("established")));
    }
    [Fact]
    public void Reordered_and_duplicate_events_do_not_reopen_terminal_call()
    {
        var state = new TeamsCallbackState();
        Assert.True(state.Register("call-1"));
        Assert.True(state.Apply(Event("terminated")));
        Assert.True(state.Apply(Event("established")));
        Assert.True(state.Apply(Event("terminated")));
        Assert.Equal("terminated", state.Read("call-1"));
    }
    [Theory]
    [InlineData("{}")]
    [InlineData("[]")]
    [InlineData("{\"value\":[]}")]
    [InlineData("{\"value\":[null]}")]
    public void Invalid_envelopes_are_rejected(string json)
    {
        Assert.False(new TeamsCallbackState().Apply(JsonDocument.Parse(json).RootElement));
    }

    [Fact]
    public void Call_and_meeting_link_survive_restart()
    {
        var directory = Path.Combine(Path.GetTempPath(), Guid.NewGuid().ToString("N"));
        var path = Path.Combine(directory, "calls.json");
        var meetingId = Guid.NewGuid();
        try
        {
            Directory.CreateDirectory(directory);
            var options = Options.Create(new TeamsCaptureOptions { CallStateFilePath = path });
            var first = new TeamsCallbackState(options);
            Assert.True(first.Register("call-1", meetingId));
            Assert.True(first.Apply(Event("established")));

            var restored = new TeamsCallbackState(options);
            Assert.Equal("established", restored.Read("call-1"));
            Assert.Equal(meetingId, restored.ReadMeetingId("call-1"));
        }
        finally
        {
            if (Directory.Exists(directory)) Directory.Delete(directory, true);
        }
    }
    private static JsonElement Event(string state) => JsonSerializer.SerializeToElement(new {
        value = new[] { new { resourceUrl = "/communications/calls/call-1", resourceData = new { state } } }
    });
}
