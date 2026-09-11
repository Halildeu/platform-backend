using TeamsCapture.Worker;
using Xunit;

namespace TeamsCapture.Worker.Tests;

public sealed class BotCaptureCoordinatorTests
{
    [Fact]
    public async Task Does_not_open_audio_gateway_until_Teams_acknowledges_recording_status()
    {
        var gateway = new RecordingGatewayClient("SES-never");
        var result = await new BotCaptureCoordinator().StartAsync(
            ValidCommand(),
            new RecordingStatusClient(false),
            gateway,
            CancellationToken.None);

        Assert.False(result.Started);
        Assert.Equal("recording_status_not_acknowledged", result.FailureCode);
        Assert.False(gateway.WasCalled);
    }

    [Fact]
    public async Task Opens_existing_gateway_only_after_recording_status_acknowledgement()
    {
        var gateway = new RecordingGatewayClient("SES-123");
        var result = await new BotCaptureCoordinator().StartAsync(
            ValidCommand(),
            new RecordingStatusClient(true),
            gateway,
            CancellationToken.None);

        Assert.True(result.Started);
        Assert.Equal("SES-123", result.SessionId);
        Assert.True(gateway.WasCalled);
    }

    [Fact]
    public async Task Rejects_missing_canonical_identity_before_any_external_call()
    {
        var recording = new RecordingStatusClient(true);
        var gateway = new RecordingGatewayClient("SES-123");
        var command = new CaptureStartCommand(Guid.Empty, Guid.NewGuid(), "corr-1");

        var result = await new BotCaptureCoordinator().StartAsync(
            command, recording, gateway, CancellationToken.None);

        Assert.False(result.Started);
        Assert.Equal("invalid_capture_identity", result.FailureCode);
        Assert.False(recording.WasCalled);
        Assert.False(gateway.WasCalled);
    }

    private static CaptureStartCommand ValidCommand() => new(Guid.NewGuid(), Guid.NewGuid(), "corr-1");

    [Fact]
    public void Does_not_report_ready_when_Teams_tenant_registration_is_missing()
    {
        var options = new TeamsCaptureOptions { Enabled = true };

        Assert.False(options.IsReadyForRegistration());
    }

    [Fact]
    public void Requires_an_https_callback_and_tenant_owned_identifiers()
    {
        var options = new TeamsCaptureOptions
        {
            Enabled = true,
            TenantId = Guid.NewGuid().ToString(),
            ApplicationId = Guid.NewGuid().ToString(),
            PublicCallbackBaseUrl = "https://teams-capture.test.example"
        };

        Assert.True(options.IsReadyForRegistration());
    }

    [Fact]
    public async Task Joins_a_calendar_meeting_without_becoming_an_audio_source()
    {
        var teams = new MeetingPresenceClient("conversation-1");
        var result = await new TeamsMeetingPresenceCoordinator().JoinAsync(
            new MeetingPresenceCommand(Guid.NewGuid(), "calendar-event-1", "corr-2"),
            teams,
            CancellationToken.None);

        Assert.True(result.Joined);
        Assert.Equal("conversation-1", result.ConversationId);
        Assert.True(teams.WasCalled);
    }

    [Fact]
    public async Task Rejects_a_join_without_the_canonical_platform_meeting_id()
    {
        var teams = new MeetingPresenceClient("conversation-1");
        var result = await new TeamsMeetingPresenceCoordinator().JoinAsync(
            new MeetingPresenceCommand(Guid.Empty, "calendar-event-1", "corr-2"),
            teams,
            CancellationToken.None);

        Assert.False(result.Joined);
        Assert.Equal("invalid_meeting_reference", result.FailureCode);
        Assert.False(teams.WasCalled);
    }

    private sealed class RecordingStatusClient(bool accepted) : IRecordingStatusClient
    {
        public bool WasCalled { get; private set; }

        public Task<bool> MarkStartedAsync(CaptureStartCommand command, CancellationToken cancellationToken)
        {
            WasCalled = true;
            return Task.FromResult(accepted);
        }
    }

    private sealed class RecordingGatewayClient(string sessionId) : IAudioGatewaySessionClient
    {
        public bool WasCalled { get; private set; }

        public Task<string?> StartSessionAsync(CaptureStartCommand command, CancellationToken cancellationToken)
        {
            WasCalled = true;
            return Task.FromResult<string?>(sessionId);
        }
    }

    private sealed class MeetingPresenceClient(string conversationId) : ITeamsMeetingPresenceClient
    {
        public bool WasCalled { get; private set; }

        public Task<TeamsJoinReceipt?> JoinAsync(
            MeetingPresenceCommand command,
            CancellationToken cancellationToken)
        {
            WasCalled = true;
            return Task.FromResult<TeamsJoinReceipt?>(new TeamsJoinReceipt(conversationId));
        }
    }
}
