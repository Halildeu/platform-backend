namespace TeamsCapture.Worker;

/// <summary>
/// Coordinates the irreversible order for a Teams capture: recording notice must be
/// acknowledged before the existing audio gateway is allowed to receive any media.
/// The worker never persists audio, transcript, or analysis output.
/// </summary>
public sealed class BotCaptureCoordinator
{
    public async Task<CaptureStartResult> StartAsync(
        CaptureStartCommand command,
        IRecordingStatusClient recordingStatusClient,
        IAudioGatewaySessionClient audioGatewaySessionClient,
        CancellationToken cancellationToken)
    {
        if (command.MeetingId == Guid.Empty || command.CaptureId == Guid.Empty)
        {
            return CaptureStartResult.Rejected("invalid_capture_identity");
        }

        var recordingAcknowledged = await recordingStatusClient
            .MarkStartedAsync(command, cancellationToken)
            .ConfigureAwait(false);

        if (!recordingAcknowledged)
        {
            return CaptureStartResult.Rejected("recording_status_not_acknowledged");
        }

        var session = await audioGatewaySessionClient
            .StartSessionAsync(command, cancellationToken)
            .ConfigureAwait(false);

        return session is null
            ? CaptureStartResult.Rejected("audio_gateway_session_not_started")
            : CaptureStartResult.Succeeded(session);
    }
}

public sealed record CaptureStartCommand(Guid MeetingId, Guid CaptureId, string CorrelationId);

public sealed record CaptureStartResult(bool Started, string? SessionId, string? FailureCode)
{
    public static CaptureStartResult Rejected(string failureCode) => new(false, null, failureCode);

    public static CaptureStartResult Succeeded(string sessionId) => new(true, sessionId, null);
}

public interface IRecordingStatusClient
{
    Task<bool> MarkStartedAsync(CaptureStartCommand command, CancellationToken cancellationToken);
}

public interface IAudioGatewaySessionClient
{
    Task<string?> StartSessionAsync(CaptureStartCommand command, CancellationToken cancellationToken);
}
