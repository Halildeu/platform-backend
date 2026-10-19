namespace TeamsCapture.Media;

/// <summary>
/// Batches contain separate unmixed slices with their receiving-clock timestamps.
/// Implementations must consume them before SendAsync returns, preserve per-source timelines,
/// recheck authorization at gateway dispatch AND durable-output boundaries, and discard on Abort.
/// AbortAsync must be idempotent and return only after the transport has stopped reading owned frames.
/// </summary>
public interface IAuthorizedUnmixedAudioTransport
{
    Task SendAsync(BotProcessingCheck scope, IReadOnlyList<OwnedAudioFrame> frames, CancellationToken cancellationToken);
    Task AbortAsync();
}

/// <summary>
/// One-shot receiver lifecycle. An authorization outage or stop is terminal; an old successful
/// response cannot restart it. Point-in-time checks cannot recall data already sent over a network.
/// </summary>
public sealed class AuthorizedAudioDispatcher : IAsyncDisposable
{
    private readonly object sync = new();
    private readonly UnmixedAudioReceiver receiver;
    private readonly IBotProcessingAuthorization authorization;
    private readonly IGraphRecordingStatus recording;
    private readonly IAuthorizedUnmixedAudioTransport transport;
    private readonly MediaCallKey call;
    private readonly Guid intentId;
    private readonly TimeProvider time;
    private readonly CancellationTokenSource stop = new();
    private bool started, terminal;
    private Task? run;
    private IReadOnlyList<OwnedAudioFrame> activeBatch = Array.Empty<OwnedAudioFrame>();

    public AuthorizedAudioDispatcher(UnmixedAudioReceiver receiver, MediaCallKey call, Guid intentId,
        IBotProcessingAuthorization authorization, IGraphRecordingStatus recording,
        IAuthorizedUnmixedAudioTransport transport, TimeProvider? time = null)
    {
        if (intentId == Guid.Empty || receiver.Call != call) throw new ArgumentException("Invalid capture binding.");
        this.receiver = receiver; this.call = call; this.intentId = intentId;
        this.authorization = authorization; this.recording = recording; this.transport = transport;
        this.time = time ?? TimeProvider.System;
    }

    public Task RunAsync(CancellationToken cancellationToken = default)
    {
        lock (sync)
        {
            if (started || terminal) throw new InvalidOperationException("Capture is terminal or already started.");
            started = true;
            return run = RunCoreAsync(cancellationToken);
        }
    }

    public async Task StopAsync()
    {
        lock (sync) { terminal = true; receiver.RevokeProcessing(); }
        await stop.CancelAsync().ConfigureAwait(false);
        // Abort is idempotent, including before Start or while authorization is in flight.
        try { await transport.AbortAsync().ConfigureAwait(false); }
        finally
        {
            // Includes frames already removed from the queue while an authorization response waits.
            lock (sync) { foreach (var frame in activeBatch) frame.Dispose(); }
        }
    }

    private async Task RunCoreAsync(CancellationToken external)
    {
        using var linked = CancellationTokenSource.CreateLinkedTokenSource(external, stop.Token);
        var ct = linked.Token;
        bool attemptedRecording = false;
        try
        {
            var initial = await authorization.CheckAsync(call, intentId, ct).ConfigureAwait(false);
            attemptedRecording = true;
            var receipt = await recording.StartAsync(call, ct).ConfigureAwait(false);
            if (receipt.Call != call) throw new InvalidOperationException("Foreign Microsoft recording receipt.");
            var current = await authorization.CheckAsync(call, intentId, ct).ConfigureAwait(false);
            RequireSameScope(initial, current);
            lock (sync)
            {
                ct.ThrowIfCancellationRequested();
                if (terminal) throw new OperationCanceledException(ct);
                receiver.AllowProcessing(call);
            }
            // No async I/O on the SDK callback. Queue remains bounded in the receiver.
            // Check every nonempty batch and at least once a second while silent.
            using var timer = new PeriodicTimer(TimeSpan.FromMilliseconds(100), time);
            var lastChecked = time.GetTimestamp();
            while (await timer.WaitForNextTickAsync(ct).ConfigureAwait(false))
            {
                IReadOnlyList<OwnedAudioFrame> batch;
                lock (sync)
                {
                    ct.ThrowIfCancellationRequested();
                    if (terminal) throw new OperationCanceledException(ct);
                    batch = activeBatch = receiver.TakeBatch(80);
                }
                try
                {
                    if (receiver.IsStopped) throw new InvalidOperationException("Native receiver stopped.");
                    if (batch.Count == 0 && time.GetElapsedTime(lastChecked) < TimeSpan.FromSeconds(1)) continue;
                    current = await authorization.CheckAsync(call, intentId, ct).ConfigureAwait(false);
                    RequireSameScope(initial, current);
                    lastChecked = time.GetTimestamp();
                    using var sendDeadline = CancellationTokenSource.CreateLinkedTokenSource(ct);
                    var remaining = current.ExpiresAt - time.GetUtcNow();
                    if (remaining <= TimeSpan.Zero) throw new InvalidOperationException("Native consent expired.");
                    sendDeadline.CancelAfter(remaining < TimeSpan.FromSeconds(3) ? remaining : TimeSpan.FromSeconds(3));
                    Task? send = null;
                    lock (sync)
                    {
                        ct.ThrowIfCancellationRequested();
                        if (terminal || receiver.IsStopped) throw new OperationCanceledException(ct);
                        if (batch.Count > 0)
                        {
                            // Source reuse/roster invalidation can arrive during the HTTP authorization wait.
                            receiver.RefreshAttribution(batch);
                            send = transport.SendAsync(current, batch, sendDeadline.Token);
                        }
                    }
                    if (send is not null) await send.ConfigureAwait(false);
                }
                finally
                {
                    lock (sync)
                    {
                        foreach (var frame in batch) frame.Dispose();
                        activeBatch = Array.Empty<OwnedAudioFrame>();
                    }
                }
            }
        }
        finally
        {
            // Discard before ending Microsoft's recording indication. Never flush on denial.
            try { await StopAsync().ConfigureAwait(false); }
            finally
            {
                if (attemptedRecording)
                {
                    using var cleanup = new CancellationTokenSource(TimeSpan.FromSeconds(10));
                    await recording.EndAsync(call, cleanup.Token).ConfigureAwait(false);
                }
            }
        }
    }

    private static void RequireSameScope(BotProcessingCheck a, BotProcessingCheck b)
    {
        // CheckedAt changes on each fresh read; all authority-bearing fields must remain immutable.
        if (a with { CheckedAt = b.CheckedAt } != b) throw new InvalidOperationException("Native processing scope changed.");
    }

    public async ValueTask DisposeAsync()
    {
        try
        {
            await StopAsync().ConfigureAwait(false);
            if (run is not null)
            {
                try { await run.ConfigureAwait(false); }
                catch (OperationCanceledException) { }
            }
        }
        finally { receiver.Dispose(); stop.Dispose(); }
    }
}
