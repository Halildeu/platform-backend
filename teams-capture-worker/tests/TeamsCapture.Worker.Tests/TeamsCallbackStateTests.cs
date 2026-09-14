using System.Text.Json;
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
    private static JsonElement Event(string state) => JsonSerializer.SerializeToElement(new {
        value = new[] { new { resourceUrl = "/communications/calls/call-1", resourceData = new { state } } }
    });
}
