using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;
using TeamsCapture.Worker;
using Xunit;

namespace TeamsCapture.Worker.Tests;

public sealed class TeamsParticipantRosterHttpTests
{
    private const string ControlKey = "01234567890123456789012345678901";

    [Theory]
    [InlineData(null, "call-1", "established", HttpStatusCode.Unauthorized, 0)]
    [InlineData("wrong", "call-1", "established", HttpStatusCode.Unauthorized, 0)]
    [InlineData(ControlKey, "unknown", "established", HttpStatusCode.NotFound, 0)]
    [InlineData(ControlKey, "call-1", "establishing", HttpStatusCode.Conflict, 0)]
    [InlineData(ControlKey, "call-1", "terminated", HttpStatusCode.Conflict, 0)]
    [InlineData(ControlKey, "call-1", "established", HttpStatusCode.OK, 1)]
    public async Task Requires_control_key_and_an_established_registered_call(string? key, string callId,
        string status, HttpStatusCode expected, int requests)
    {
        using var factory = new Factory(status);
        using var client = factory.CreateClient();
        if (key is not null) client.DefaultRequestHeaders.Add("X-Teams-Control-Key", key);
        var response = await client.GetAsync($"/api/teams/calls/{callId}/participants");
        Assert.Equal(expected, response.StatusCode);
        Assert.True(response.Headers.CacheControl?.NoStore);
        Assert.Equal(requests, factory.Roster.Requests);
        if (expected == HttpStatusCode.OK)
        {
            var body = await response.Content.ReadFromJsonAsync<TeamsParticipantRoster>();
            Assert.NotNull(body);
            Assert.Equal(factory.MeetingId, body.MeetingId);
            Assert.Equal("roster-only-live-media-not-connected", body.AttributionStatus);
        }
    }

    [Theory]
    [InlineData("unavailable", HttpStatusCode.BadGateway)]
    [InlineData("wrong-meeting", HttpStatusCode.BadGateway)]
    [InlineData("wrong-call", HttpStatusCode.BadGateway)]
    [InlineData("terminated-during-read", HttpStatusCode.Conflict)]
    public async Task Never_exposes_a_failed_cross_meeting_or_terminal_response(string mode, HttpStatusCode expected)
    {
        using var factory = new Factory("established", mode);
        using var client = factory.CreateClient();
        client.DefaultRequestHeaders.Add("X-Teams-Control-Key", ControlKey);
        var response = await client.GetAsync("/api/teams/calls/call-1/participants");
        Assert.Equal(expected, response.StatusCode);
        Assert.DoesNotContain("Test Speaker", await response.Content.ReadAsStringAsync());
    }

    private sealed class Factory(string status, string mode = "success") : WebApplicationFactory<Program>
    {
        public Guid MeetingId { get; } = Guid.NewGuid();
        public RosterClient Roster { get; private set; } = null!;

        protected override void ConfigureWebHost(IWebHostBuilder builder)
        {
            builder.UseContentRoot(AppContext.BaseDirectory);
            builder.ConfigureLogging(logging => logging.ClearProviders());
            builder.ConfigureServices(services =>
            {
                services.RemoveAll<IOptions<TeamsCaptureOptions>>();
                services.AddSingleton(Options.Create(new TeamsCaptureOptions
                {
                    Enabled = true, TenantId = Guid.NewGuid().ToString(), ApplicationId = Guid.NewGuid().ToString(),
                    PublicCallbackBaseUrl = "https://bot.test.example", ControlApiKey = ControlKey,
                    CallStateFilePath = Path.GetFullPath("unused-calls.json"),
                    CalendarStateFilePath = Path.GetFullPath("unused-calendar.json")
                }));
                var state = new TeamsCallbackState();
                state.Register("call-1", MeetingId);
                SetState(state, status);
                services.RemoveAll<TeamsCallbackState>();
                services.AddSingleton(state);
                Roster = new RosterClient(MeetingId, mode, state);
                services.RemoveAll<ITeamsParticipantRosterClient>();
                services.AddSingleton<ITeamsParticipantRosterClient>(Roster);
            });
        }
    }

    private static void SetState(TeamsCallbackState state, string value) => state.Apply(JsonSerializer.SerializeToElement(new
    {
        value = new[] { new { resourceUrl = "/communications/calls/call-1", resourceData = new { state = value } } }
    }));

    private sealed class RosterClient(Guid meetingId, string mode, TeamsCallbackState state) : ITeamsParticipantRosterClient
    {
        public int Requests { get; private set; }
        public Task<TeamsParticipantRoster?> ReadAsync(string callId, CancellationToken cancellationToken)
        {
            Requests++;
            if (mode == "terminated-during-read") SetState(state, "terminated");
            return Task.FromResult<TeamsParticipantRoster?>(mode == "unavailable" ? null : new TeamsParticipantRoster(
                mode == "wrong-call" ? "another-call" : callId,
                mode == "wrong-meeting" ? Guid.NewGuid() : meetingId, DateTimeOffset.UtcNow,
                [new TeamsParticipant("p1", "u1", "Test Speaker", false, false, ["42"])]));
        }
    }
}
