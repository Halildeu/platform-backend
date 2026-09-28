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

    [Fact]
    public async Task Conflicting_calendar_ids_for_one_meeting_cannot_fill_durable_capacity()
    {
        using var factory = new Factory();
        using var client = factory.CreateClient();
        client.DefaultRequestHeaders.Add("X-Teams-Control-Key", ControlKey);
        var meetingId = Guid.NewGuid();
        var organizer = Guid.NewGuid().ToString();
        for (var i = 0; i < 1000; i++)
        {
            var response = await client.PostAsJsonAsync($"/api/teams/meetings/{meetingId}/join", new
            {
                calendarEventId = $"event-{i}", threadId = "19:meeting@thread.v2", messageId = "0",
                organizerUserId = organizer, correlationId = "corr-valid"
            });
            Assert.Equal(i == 0 ? HttpStatusCode.Accepted : HttpStatusCode.BadRequest, response.StatusCode);
        }
        Assert.Equal(1, factory.TeamsClient.Requests);
        var settings = factory.Services.GetRequiredService<IOptions<TeamsCaptureOptions>>();
        var restarted = new DurableTeamsCalendarMeetingResolver(settings);
        Assert.NotNull(await restarted.ResolveAsync("event-0", CancellationToken.None));
        Assert.Null(await restarted.ResolveAsync("event-999", CancellationToken.None));
        using var snapshot = System.Text.Json.JsonDocument.Parse(SnapshotTestStorage.Read(settings.Value.CalendarStateFilePath!));
        Assert.Single(snapshot.RootElement.EnumerateObject());
        var valid = await client.PostAsJsonAsync($"/api/teams/meetings/{Guid.NewGuid()}/join", new
        {
            calendarEventId = "other-meeting", threadId = "19:other@thread.v2", messageId = "0",
            organizerUserId = organizer, correlationId = "corr-other"
        });
        Assert.Equal(HttpStatusCode.Accepted, valid.StatusCode);
        Assert.Equal(2, factory.TeamsClient.Requests);
    }

    [Fact]
    public async Task Invalid_correlation_ids_do_not_consume_durable_calendar_capacity()
    {
        using var factory = new Factory();
        using var client = factory.CreateClient();
        client.DefaultRequestHeaders.Add("X-Teams-Control-Key", ControlKey);
        for (var i = 0; i < 1000; i++)
        {
            var response = await client.PostAsJsonAsync($"/api/teams/meetings/{Guid.NewGuid()}/join", new
            {
                calendarEventId = $"invalid-{i}", threadId = "19:meeting@thread.v2", messageId = "0",
                organizerUserId = Guid.NewGuid().ToString(), correlationId = (i % 4) switch
                {
                    0 => null, 1 => "", 2 => "bad/value", _ => new string('x', 129)
                }
            });
            Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
        }
        Assert.Equal(0, factory.TeamsClient.Requests);
        Assert.Empty(Directory.GetFiles(factory.DirectoryPath));
        var settings = factory.Services.GetRequiredService<IOptions<TeamsCaptureOptions>>();
        var restarted = new DurableTeamsCalendarMeetingResolver(settings);
        Assert.Null(await restarted.ResolveAsync("invalid-0", CancellationToken.None));
        var valid = await client.PostAsJsonAsync($"/api/teams/meetings/{Guid.NewGuid()}/join", new
        {
            calendarEventId = "valid", threadId = "19:meeting@thread.v2", messageId = "0",
            organizerUserId = Guid.NewGuid().ToString(), correlationId = "corr-valid"
        });
        Assert.Equal(HttpStatusCode.Accepted, valid.StatusCode);
        Assert.Equal(1, factory.TeamsClient.Requests);
    }

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
        public string DirectoryPath => directory;
        public FakeTeamsClient TeamsClient { get; } = new();

        protected override void ConfigureWebHost(IWebHostBuilder builder)
        {
            Directory.CreateDirectory(directory);
            builder.UseContentRoot(AppContext.BaseDirectory);
            builder.ConfigureLogging(logging => logging.ClearProviders());
            builder.ConfigureServices(services =>
            {
                services.RemoveAll<Microsoft.Extensions.Hosting.IHostedService>();
                services.RemoveAll<IOptions<TeamsCaptureOptions>>();
                services.AddSingleton(Options.Create(new TeamsCaptureOptions
                {
                    Enabled = true,
                    ClientSecret = "synthetic-test-credential",
                    TenantId = Guid.NewGuid().ToString(),
                    ApplicationId = Guid.NewGuid().ToString(),
                    PublicCallbackBaseUrl = "https://bot.test.example",
                    ControlApiKey = ControlKey,
                    CallStateFilePath = Path.Combine(directory, "calls.json"),
                    CalendarStateFilePath = Path.Combine(directory, "calendar.json")
                }));
                services.RemoveAll<ITeamsMeetingPresenceClient>();
                services.AddSingleton<ITeamsMeetingPresenceClient>(TeamsClient);
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
        public int Requests { get; private set; }
        public Task<TeamsJoinReceipt?> JoinAsync(MeetingPresenceCommand command, CancellationToken cancellationToken)
        {
            Requests++;
            return Task.FromResult<TeamsJoinReceipt?>(new TeamsJoinReceipt($"call-{Requests}"));
        }
    }
}
