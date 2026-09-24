using System.Net;
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

public sealed class TeamsLifecycleHttpTests
{
    [Theory]
    [InlineData(null, "call-1", HttpStatusCode.Unauthorized, 0)]
    [InlineData("wrong", "call-1", HttpStatusCode.Unauthorized, 0)]
    [InlineData("valid", "unknown", HttpStatusCode.NotFound, 0)]
    [InlineData("valid", "call-1", HttpStatusCode.NoContent, 1)]
    public async Task Leave_only_operates_on_authorized_owned_calls(string? key, string callId, HttpStatusCode expected, int leaves)
    {
        using var factory = new Factory();
        using var client = factory.CreateClient();
        if (key is not null) client.DefaultRequestHeaders.Add("X-Teams-Control-Key", key == "valid" ? new string('k', 32) : key);
        var response = await client.PostAsync($"/api/teams/calls/{callId}/leave", null);
        Assert.Equal(expected, response.StatusCode);
        Assert.True(response.Headers.CacheControl?.NoStore);
        Assert.Equal(leaves, factory.Lifecycle.Leaves);
        if (leaves == 1)
        {
            Assert.Equal("terminated", factory.State.Read("call-1"));
            Assert.Equal(HttpStatusCode.NoContent, (await client.PostAsync($"/api/teams/calls/{callId}/leave", null)).StatusCode);
            Assert.Equal(1, factory.Lifecycle.Leaves);
        }
    }

    [Fact]
    public async Task Failed_leave_does_not_claim_the_call_ended()
    {
        using var factory = new Factory();
        factory.Lifecycle.LeaveResult = TeamsCallOperationResult.Unconfirmed;
        using var client = factory.CreateClient();
        client.DefaultRequestHeaders.Add("X-Teams-Control-Key", new string('k', 32));
        Assert.Equal(HttpStatusCode.BadGateway, (await client.PostAsync("/api/teams/calls/call-1/leave", null)).StatusCode);
        Assert.Equal("establishing", factory.State.Read("call-1"));
    }

    [Fact]
    public async Task Readiness_is_private_redacted_and_never_claims_live_capture()
    {
        using var factory = new Factory();
        using var client = factory.CreateClient();
        Assert.Equal(HttpStatusCode.Unauthorized, (await client.GetAsync("/api/teams/readiness")).StatusCode);
        client.DefaultRequestHeaders.Add("X-Teams-Control-Key", new string('k', 32));
        var response = await client.GetAsync("/api/teams/readiness");
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.True(response.Headers.CacheControl?.NoStore);
        var body = await response.Content.ReadAsStringAsync();
        Assert.Contains("\"controlPlaneConfigured\":true", body);
        Assert.Contains("\"liveAudio\":false", body);
        Assert.Contains("\"teamsSidePanel\":false", body);
        using var document = System.Text.Json.JsonDocument.Parse(body);
        var identity = document.RootElement.GetProperty("configuredIdentity");
        Assert.Equal("application-client-credentials", identity.GetProperty("authentication").GetString());
        Assert.Equal(factory.Settings.TenantId, identity.GetProperty("tenantId").GetString());
        Assert.Equal(factory.Settings.ApplicationId, identity.GetProperty("applicationId").GetString());
        Assert.DoesNotContain("synthetic-test-credential", body);
        Assert.DoesNotContain(new string('k', 32), body);
        Assert.DoesNotContain(factory.DirectoryPath, body);
    }

    [Fact]
    public async Task Join_status_requires_authorization_and_distinguishes_unknown_attempt()
    {
        using var factory = new Factory();
        var meetingId = Guid.NewGuid();
        factory.State.ReserveJoin(meetingId, "event-1");
        using var client = factory.CreateClient();
        var url = $"/api/teams/meetings/{meetingId}/join-status";
        Assert.Equal(HttpStatusCode.Unauthorized, (await client.GetAsync(url)).StatusCode);
        client.DefaultRequestHeaders.Add("X-Teams-Control-Key", new string('k', 32));
        Assert.Contains("join_outcome_unconfirmed", await client.GetStringAsync(url));
        Assert.Equal(HttpStatusCode.NotFound, (await client.GetAsync($"/api/teams/meetings/{Guid.NewGuid()}/join-status")).StatusCode);
    }

    private sealed class Factory : WebApplicationFactory<Program>
    {
        private readonly TeamsOperationalTests.Fixture fixture = new();
        public string DirectoryPath => fixture.DirectoryPath;
        public TeamsCaptureOptions Settings => fixture.Options.Value;
        public TeamsCallbackState State { get; } = new();
        public TeamsOperationalTests.LifecycleStub Lifecycle { get; } = new();
        protected override void ConfigureWebHost(IWebHostBuilder builder)
        {
            builder.UseContentRoot(AppContext.BaseDirectory);
            builder.ConfigureLogging(logging => logging.ClearProviders());
            builder.ConfigureServices(services =>
            {
                services.RemoveAll<IHostedService>();
                services.RemoveAll<IOptions<TeamsCaptureOptions>>();
                services.AddSingleton(fixture.Options);
                State.Register("call-1", Guid.NewGuid());
                services.RemoveAll<TeamsCallbackState>();
                services.AddSingleton(State);
                services.RemoveAll<ITeamsCallLifecycleClient>();
                services.AddSingleton<ITeamsCallLifecycleClient>(Lifecycle);
            });
        }
        protected override void Dispose(bool disposing) { base.Dispose(disposing); if (disposing) fixture.Dispose(); }
    }
}
