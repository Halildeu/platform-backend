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
}
