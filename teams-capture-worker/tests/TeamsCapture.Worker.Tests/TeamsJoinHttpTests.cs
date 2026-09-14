using System.Net;
using System.Net.Http.Json;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.DependencyInjection.Extensions;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;
using TeamsCapture.Worker;
using Xunit;

namespace TeamsCapture.Worker.Tests;

public sealed class TeamsJoinHttpTests
{
    private const string ControlKey = "01234567890123456789012345678901";

    [Theory]
    [InlineData(null, HttpStatusCode.Unauthorized)]
    [InlineData("wrong", HttpStatusCode.Unauthorized)]
    [InlineData(ControlKey, HttpStatusCode.Accepted)]
    public async Task Join_requires_exact_control_key(string? key, HttpStatusCode expected)
    {
        using var factory = new Factory();
        using var client = factory.CreateClient();
        if (key is not null) client.DefaultRequestHeaders.Add("X-Teams-Control-Key", key);
        var response = await client.PostAsJsonAsync($"/api/teams/meetings/{Guid.NewGuid()}/join", new
        {
            calendarEventId = "event-1",
            threadId = "19:meeting@thread.v2",
            messageId = "0",
            organizerUserId = Guid.NewGuid().ToString(),
            correlationId = "corr-1"
        });
        Assert.Equal(expected, response.StatusCode);
    }

    private sealed class Factory : WebApplicationFactory<Program>
    {
        private readonly string directory = Path.Combine(Path.GetTempPath(), Guid.NewGuid().ToString("N"));

        protected override void ConfigureWebHost(IWebHostBuilder builder)
        {
            builder.UseContentRoot(AppContext.BaseDirectory);
            builder.ConfigureLogging(logging => logging.ClearProviders());
            builder.ConfigureServices(services =>
            {
                services.RemoveAll<IOptions<TeamsCaptureOptions>>();
                services.AddSingleton(Options.Create(new TeamsCaptureOptions
                {
                    Enabled = true,
                    TenantId = Guid.NewGuid().ToString(),
                    ApplicationId = Guid.NewGuid().ToString(),
                    PublicCallbackBaseUrl = "https://bot.test.example",
                    ControlApiKey = ControlKey,
                    CallStateFilePath = Path.Combine(directory, "calls.json"),
                    CalendarStateFilePath = Path.Combine(directory, "calendar.json")
                }));
                services.RemoveAll<ITeamsMeetingPresenceClient>();
                services.AddSingleton<ITeamsMeetingPresenceClient>(new FakeTeamsClient());
            });
        }

        protected override void Dispose(bool disposing)
        {
            base.Dispose(disposing);
            if (Directory.Exists(directory)) Directory.Delete(directory, true);
        }
    }

    private sealed class FakeTeamsClient : ITeamsMeetingPresenceClient
    {
        public Task<TeamsJoinReceipt?> JoinAsync(MeetingPresenceCommand command, CancellationToken cancellationToken) =>
            Task.FromResult<TeamsJoinReceipt?>(new TeamsJoinReceipt("call-1"));
    }
}
