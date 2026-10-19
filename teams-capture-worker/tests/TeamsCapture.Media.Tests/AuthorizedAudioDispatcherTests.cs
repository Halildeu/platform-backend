using System.Runtime.InteropServices;
using TeamsCapture.Media;
using Xunit;

namespace TeamsCapture.Media.Tests;

public class AuthorizedAudioDispatcherTests
{
    private readonly MediaCallKey call = new(Guid.NewGuid(), Guid.NewGuid(), "call-1", Guid.NewGuid());
    private readonly Guid intent = Guid.NewGuid();

    [Fact] public async Task UnmixedSlicesRemainSeparateAndAreZeroedAfterTransportAndTerminalStop()
    {
        using var receiver = Receiver(); var auth = new Authorization(Check()); var recording = new Recording(); var transport = new Transport();
        await using var dispatcher = new AuthorizedAudioDispatcher(receiver, call, intent, auth, recording, transport);
        var running = dispatcher.RunAsync(); receiver.Process(new Lease(17, 42));
        await transport.Sent.Task.WaitAsync(TimeSpan.FromSeconds(3));
        await dispatcher.StopAsync(); await Assert.ThrowsAnyAsync<OperationCanceledException>(() => running);
        Assert.Equal(new uint[] { 17, 42 }, transport.Frames.Select(f => f.SourceId));
        Assert.Equal(transport.Frames[0].ReceivedAt, transport.Frames[1].ReceivedAt);
        Assert.All(transport.Frames, frame => Assert.All(frame.Pcm.ToArray(), b => Assert.Equal(0, b)));
        Assert.True(auth.Count >= 3); Assert.Equal(1, recording.Starts); Assert.Equal(1, recording.Ends);
        await Assert.ThrowsAsync<InvalidOperationException>(() => dispatcher.RunAsync());
    }

    [Fact] public async Task LateSuccessfulAuthorizationCannotReopenStoppedReceiver()
    {
        using var receiver = Receiver(); var late = new TaskCompletionSource<BotProcessingCheck>(TaskCreationOptions.RunContinuationsAsynchronously);
        var reached = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var auth = new Authorization(Check()) { OnCheck = count => { if (count == 3) { reached.SetResult(); return late.Task; } return null; } };
        var transport = new Transport(); var recording = new Recording();
        await using var dispatcher = new AuthorizedAudioDispatcher(receiver, call, intent, auth, recording, transport);
        var running = dispatcher.RunAsync(); receiver.Process(new Lease(17));
        await reached.Task.WaitAsync(TimeSpan.FromSeconds(3));
        // Retain the actual owned copy to verify Stop's memory boundary while auth remains unresolved.
        var held = (IReadOnlyList<OwnedAudioFrame>)typeof(AuthorizedAudioDispatcher)
            .GetField("activeBatch", System.Reflection.BindingFlags.NonPublic | System.Reflection.BindingFlags.Instance)!.GetValue(dispatcher)!;
        Assert.NotEmpty(held); Assert.Contains((byte)17, held[0].Pcm.ToArray());
        await dispatcher.StopAsync();
        Assert.All(held, frame => Assert.All(frame.Pcm.ToArray(), b => Assert.Equal(0, b)));
        Assert.False(late.Task.IsCompleted); late.SetResult(auth.Value);
        await Assert.ThrowsAnyAsync<OperationCanceledException>(() => running);
        Assert.Empty(transport.Frames); Assert.True(receiver.IsStopped); Assert.Equal(1, recording.Ends);
    }

    [Theory] [InlineData("denied")] [InlineData("scope")] [InlineData("send")]
    public async Task AnyFailureDiscardsAndCannotRetrySameSession(string failure)
    {
        using var receiver = Receiver(); var auth = new Authorization(Check()); var recording = new Recording(); var transport = new Transport();
        auth.OnCheck = n => n != 3 ? null : failure switch {
            "denied" => Task.FromException<BotProcessingCheck>(new InvalidOperationException("denied")),
            "scope" => Task.FromResult(auth.Value with { CompanyId = 999 }), _ => null
        };
        transport.Fail = failure == "send";
        var dispatcher = new AuthorizedAudioDispatcher(receiver, call, intent, auth, recording, transport);
        var running = dispatcher.RunAsync(); receiver.Process(new Lease(17));
        await Assert.ThrowsAsync<InvalidOperationException>(() => running.WaitAsync(TimeSpan.FromSeconds(3)));
        Assert.True(receiver.IsStopped); Assert.Equal(1, recording.Ends); Assert.True(transport.Aborts > 0);
        Assert.All(transport.Frames, frame => Assert.All(frame.Pcm.ToArray(), b => Assert.Equal(0, b)));
        await Assert.ThrowsAsync<InvalidOperationException>(() => dispatcher.RunAsync());
        await Assert.ThrowsAsync<InvalidOperationException>(() => dispatcher.DisposeAsync().AsTask());
    }

    [Fact] public async Task MissingMicrosoftAcknowledgementNeverOpensReceiver()
    {
        using var receiver = Receiver(); var recording = new Recording { Reject = true }; var transport = new Transport();
        var dispatcher = new AuthorizedAudioDispatcher(receiver, call, intent, new Authorization(Check()), recording, transport);
        await Assert.ThrowsAsync<InvalidOperationException>(() => dispatcher.RunAsync());
        Assert.True(receiver.IsStopped); Assert.Empty(transport.Frames); Assert.Equal(1, recording.Ends);
        await Assert.ThrowsAsync<InvalidOperationException>(() => dispatcher.DisposeAsync().AsTask());
    }

    [Theory] [InlineData(false)] [InlineData(true)]
    public async Task RosterChangeDuringAuthorizationCannotSendOldMatchedName(bool reused)
    {
        var map = new TemporalSpeakerMap(call, TimeSpan.FromSeconds(5));
        map.ApplySnapshot(call, 1, 1, [TemporalSpeakerMapTests.Person("a", "Original", 17)]);
        using var receiver = new UnmixedAudioReceiver(map);
        var late = new TaskCompletionSource<BotProcessingCheck>(TaskCreationOptions.RunContinuationsAsynchronously);
        var reached = new TaskCompletionSource(TaskCreationOptions.RunContinuationsAsynchronously);
        var auth = new Authorization(Check()) { OnCheck = count => { if (count == 3) { reached.SetResult(); return late.Task; } return null; } };
        var transport = new Transport();
        await using var dispatcher = new AuthorizedAudioDispatcher(receiver, call, intent, auth, new Recording(), transport);
        var running = dispatcher.RunAsync(); receiver.Process(new Lease(17));
        await reached.Task.WaitAsync(TimeSpan.FromSeconds(3));
        if (reused) map.ApplySnapshot(call, 2, 20000000, [TemporalSpeakerMapTests.Person("b", "Different", 17)]);
        else map.Clear();
        late.SetResult(auth.Value); await transport.Sent.Task.WaitAsync(TimeSpan.FromSeconds(3));
        await dispatcher.StopAsync(); await Assert.ThrowsAnyAsync<OperationCanceledException>(() => running);
        Assert.Null(transport.Frames[0].Attribution.Speaker);
        Assert.Equal(reused ? "source_reused" : "roster_unavailable", transport.Frames[0].Attribution.Status);
    }

    private UnmixedAudioReceiver Receiver() => new(new TemporalSpeakerMap(call, TimeSpan.FromSeconds(5)));
    private BotProcessingCheck Check() => new(1, "TEAMS_LIVE_TRANSCRIPTION", intent, call.MeetingId, 2,
        new("teams-capture-worker", call.CallId, call.MediaSessionId.ToString("D")), Guid.NewGuid(), Guid.NewGuid(), 35, 42,
        call.TenantId, Guid.NewGuid(), DateTimeOffset.UtcNow, DateTimeOffset.UtcNow.AddMinutes(2));
    private sealed class Authorization(BotProcessingCheck value) : IBotProcessingAuthorization
    {
        public int Count; public BotProcessingCheck Value = value; public Func<int, Task<BotProcessingCheck>?>? OnCheck;
        public Task<BotProcessingCheck> CheckAsync(MediaCallKey call, Guid intent, CancellationToken ct)
        { Count++; return OnCheck?.Invoke(Count) ?? Task.FromResult(Value); }
    }
    private sealed class Recording : IGraphRecordingStatus
    {
        public int Starts, Ends; public bool Reject;
        public Task<RecordingStatusReceipt> StartAsync(MediaCallKey call, CancellationToken ct)
        { Starts++; return Reject ? Task.FromException<RecordingStatusReceipt>(new InvalidOperationException("unconfirmed")) : Task.FromResult(new RecordingStatusReceipt(call)); }
        public Task EndAsync(MediaCallKey call, CancellationToken ct) { Ends++; return Task.CompletedTask; }
    }
    private sealed class Transport : IAuthorizedUnmixedAudioTransport
    {
        public readonly TaskCompletionSource Sent = new(TaskCreationOptions.RunContinuationsAsynchronously);
        public OwnedAudioFrame[] Frames = []; public int Aborts; public bool Fail;
        public Task SendAsync(BotProcessingCheck scope, IReadOnlyList<OwnedAudioFrame> frames, CancellationToken ct)
        { Frames = frames.ToArray(); Sent.TrySetResult(); return Fail ? Task.FromException(new InvalidOperationException("transport")) : Task.CompletedTask; }
        public Task AbortAsync() { Aborts++; return Task.CompletedTask; }
    }
    private sealed class Lease(params uint[] sources) : IAudioBufferLease
    {
        private readonly NativePcmSlice[] slices = sources.Select(source => {
            var data = Marshal.AllocHGlobal(640); Marshal.Copy(Enumerable.Repeat((byte)source, 640).ToArray(), 0, data, 640);
            return new NativePcmSlice(source, data, 640, 0);
        }).ToArray();
        public bool IsPcm16K => true; public bool IsSilence => false; public long ReceivedAt => 10000000;
        public IReadOnlyList<NativePcmSlice>? Slices => slices;
        public void Dispose() { foreach (var slice in slices) Marshal.FreeHGlobal(slice.Data); }
    }
}
