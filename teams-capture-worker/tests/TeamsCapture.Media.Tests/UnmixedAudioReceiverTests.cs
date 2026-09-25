using System.Runtime.InteropServices;
using System.Reflection;
using Microsoft.Data.Sqlite;
using Microsoft.Skype.Bots.Media;
using TeamsCapture.Media;
using Xunit;

namespace TeamsCapture.Media.Tests;

public class UnmixedAudioReceiverTests
{
    private const long T = TimeSpan.TicksPerSecond;
    private static (TemporalSpeakerMap Map, UnmixedAudioReceiver Receiver) Create(int capacity = 8)
    {
        var map = new TemporalSpeakerMap(TemporalSpeakerMapTests.Key(), TimeSpan.FromSeconds(5));
        map.ApplySnapshot(map.Key, 1, T, [TemporalSpeakerMapTests.Person("a", "Mehmet", 17),
            TemporalSpeakerMapTests.Person("b", "Zeynep", 42)]);
        return (map, new UnmixedAudioReceiver(map, capacity));
    }

    [Fact]
    public async Task NativeMemoryIsCopiedBeforeDisposalAndTwoSpeakersRemainSeparate()
    {
        var (map, receiver) = Create();
        using (receiver)
        {
            receiver.AllowProcessing(map.Key);
            var native = new TestLease(2 * T, 17, 42);
            receiver.Process(native);
            Assert.Equal(1, native.DisposeCount);
            await using var reader = receiver.ReadAllAsync().GetAsyncEnumerator();
            Assert.True(await reader.MoveNextAsync());
            var first = reader.Current;
            Assert.Equal("a", first.Attribution.Speaker!.ParticipantId);
            Assert.Equal((byte)17, first.Pcm.Span[0]);
            Assert.Equal(0, first.OriginalSenderTimestamp); // Zero is valid at SDK startup.
            Assert.True(await reader.MoveNextAsync());
            using var second = reader.Current;
            Assert.Equal("b", second.Attribution.Speaker!.ParticipantId);
            Assert.Equal((byte)42, second.Pcm.Span[0]);
            first.Dispose();
            Assert.All(first.Pcm.ToArray(), value => Assert.Equal(0, value));
        }
    }

    [Fact]
    public async Task PermissionStartsClosedAndRevocationDiscardsQueueAndIsTerminal()
    {
        var (map, receiver) = Create();
        using (receiver)
        {
            var before = new TestLease(2 * T, 17);
            receiver.Process(before);
            Assert.Equal(1, before.DisposeCount);
            Assert.Equal(0, before.SliceReads);
            receiver.AllowProcessing(map.Key);
            receiver.Process(new TestLease(3 * T, 17));
            receiver.RevokeProcessing();
            var after = new TestLease(4 * T, 17);
            receiver.Process(after);
            Assert.Equal(1, after.DisposeCount);
            Assert.Throws<InvalidOperationException>(() => receiver.AllowProcessing(map.Key));
            await using var reader = receiver.ReadAllAsync().GetAsyncEnumerator();
            Assert.False(await reader.MoveNextAsync());
            Assert.Equal("processing_revoked", receiver.Failure);
        }
    }

    [Fact]
    public async Task QueueOverflowStopsAndDisposesEveryLeaseInsteadOfSilentlyDroppingAudio()
    {
        var (map, receiver) = Create(1);
        using (receiver)
        {
            receiver.AllowProcessing(map.Key);
            var native = new TestLease(2 * T, 17, 42);
            receiver.Process(native);
            Assert.Equal(1, native.DisposeCount);
            Assert.Equal("audio_backpressure", receiver.Failure);
            await using var reader = receiver.ReadAllAsync().GetAsyncEnumerator();
            Assert.False(await reader.MoveNextAsync());
        }
    }

    [Fact]
    public async Task UnmappedSourceKeepsAudioButDoesNotBorrowAnotherPersonsName()
    {
        var (map, receiver) = Create();
        using (receiver)
        {
            receiver.AllowProcessing(map.Key);
            receiver.Process(new TestLease(2 * T, 99));
            await using var reader = receiver.ReadAllAsync().GetAsyncEnumerator();
            Assert.True(await reader.MoveNextAsync());
            using var frame = reader.Current;
            Assert.Null(frame.Attribution.Speaker);
            Assert.Equal("unknown_source", frame.Attribution.Status);
            Assert.Equal((byte)99, frame.Pcm.Span[0]);
        }
    }

    [Theory]
    [InlineData(false, false, "unmixed_audio_unavailable")]
    [InlineData(true, false, null)]
    [InlineData(false, true, "invalid_audio_clock_or_format")]
    public void MissingUnmixedAudioNeverFallsBackToMixedData(bool silence, bool invalidFormat, string? expected)
    {
        var (map, receiver) = Create();
        using (receiver)
        {
            receiver.AllowProcessing(map.Key);
            var native = new TestLease(2 * T) { MissingSlices = true, IsSilence = silence, IsPcm16K = !invalidFormat };
            receiver.Process(native);
            Assert.Equal(expected, receiver.Failure);
            Assert.Equal(1, native.DisposeCount);
        }
    }

    [Theory]
    [InlineData("reuse", "source_reused")]
    [InlineData("invalidate", "roster_unavailable")]
    [InlineData("transition", "roster_transition")]
    public async Task RosterChangesWhileQueuedAreCheckedBeforeDelivery(string change, string expected)
    {
        var (map, receiver) = Create();
        using (receiver)
        {
            receiver.AllowProcessing(map.Key);
            receiver.Process(new TestLease(2 * T, 17));
            if (change == "invalidate") map.Clear();
            else if (change == "reuse")
                map.ApplySnapshot(map.Key, 2, 3 * T, [TemporalSpeakerMapTests.Person("b", "Zeynep", 17)]);
            else
                map.ApplySnapshot(map.Key, 2, 2 * T + TimeSpan.TicksPerMillisecond,
                    [TemporalSpeakerMapTests.Person("a", "Mehmet", 17)]);
            await using var reader = receiver.ReadAllAsync().GetAsyncEnumerator();
            Assert.True(await reader.MoveNextAsync());
            using var frame = reader.Current;
            Assert.Equal(expected, frame.Attribution.Status);
            Assert.Null(frame.Attribution.Speaker);
            Assert.Equal(2 * T, frame.ReceivedAt);
            Assert.Equal((byte)17, frame.Pcm.Span[0]);
        }
    }

    [Theory]
    [InlineData(0)]
    [InlineData(3)]
    [InlineData(6402)]
    public void InvalidPcmSizeIsRejectedBeforeAnyPointerRead(int length)
    {
        var (map, receiver) = Create();
        using (receiver)
        {
            receiver.AllowProcessing(map.Key);
            var native = new TestLease(2 * T, 17) { OverrideLength = length };
            receiver.Process(native);
            Assert.Equal("invalid_unmixed_frame", receiver.Failure);
            Assert.Equal(1, native.DisposeCount);
        }
    }

    [Fact]
    public void ClockResetRequiresNewSessionAndForeignScopeCannotEnableProcessing()
    {
        var (map, receiver) = Create();
        using (receiver)
        {
            Assert.Throws<InvalidOperationException>(() => receiver.AllowProcessing(map.Key with { CallId = "other" }));
            receiver.AllowProcessing(map.Key);
            receiver.Process(new TestLease(2 * T, 17));
            receiver.Process(new TestLease(T, 17));
            Assert.Equal("invalid_audio_clock_or_format", receiver.Failure);
        }
    }

    [Fact]
    public void RealSdkBoundaryUsesUnmixedReceiveOnlyPcmAndDisposesSdkOwner()
    {
        var settings = UnmixedAudioReceiver.CreateSocketSettings();
        Assert.Equal(StreamDirection.Recvonly, settings.StreamDirections);
        Assert.Equal(AudioFormat.Pcm16K, settings.SupportedAudioFormat);
        Assert.True(settings.ReceiveUnmixedMeetingAudio);
        var sdkOwner = new SilenceSdkBuffer();
        var (map, receiver) = Create();
        using (receiver)
        {
            receiver.AllowProcessing(map.Key);
            receiver.Process(new SdkBufferLease(sdkOwner));
            Assert.Equal(1, sdkOwner.DisposeCount);
            Assert.Null(receiver.Failure);
        }
    }

    private sealed class SilenceSdkBuffer : AudioMediaBuffer
    {
        public int DisposeCount { get; private set; }
        public SilenceSdkBuffer() { AudioFormat = AudioFormat.Pcm16K; Timestamp = 2 * T; IsSilence = true; }
        protected override void Dispose(bool disposing) { DisposeCount++; }
    }

    [Fact]
    public void SocketAttachmentDisposesEventsAndUnsubscribesOnRevocation()
    {
        var (map, receiver) = Create();
        using (receiver)
        {
            var socket = DispatchProxy.Create<IAudioSocket, SocketProxy>();
            var proxy = (SocketProxy)socket;
            Assert.Throws<InvalidOperationException>(() => receiver.Attach(map.Key with { CallId = "other" }, socket));
            Assert.Null(proxy.Handler);
            receiver.Attach(map.Key, socket);
            Assert.NotNull(proxy.Handler);
            Assert.Throws<InvalidOperationException>(() => receiver.Attach(map.Key, socket));
            var before = new SilenceSdkBuffer();
            proxy.Handler!(socket, new AudioMediaReceivedEventArgs { Buffer = before });
            Assert.Equal(1, before.DisposeCount);
            receiver.AllowProcessing(map.Key);
            var capturedCallback = proxy.Handler;
            var allowed = new SilenceSdkBuffer();
            capturedCallback!(socket, new AudioMediaReceivedEventArgs { Buffer = allowed });
            Assert.Equal(1, allowed.DisposeCount);
            Assert.Null(receiver.Failure);
            receiver.RevokeProcessing();
            Assert.Null(proxy.Handler);
            // A callback already captured by the SDK can arrive after unsubscription.
            var late = new SilenceSdkBuffer();
            capturedCallback(socket, new AudioMediaReceivedEventArgs { Buffer = late });
            Assert.Equal(1, late.DisposeCount);
            Assert.Equal("processing_revoked", receiver.Failure);
        }
    }

    [Fact]
    public void SdkTransitiveSqliteUsesPatchedNativeLibrary()
    {
        using var connection = new SqliteConnection("Data Source=:memory:");
        connection.Open();
        using var command = connection.CreateCommand();
        command.CommandText = "SELECT sqlite_version()";
        var version = Version.Parse((string)command.ExecuteScalar()!);
        Assert.True(version >= new Version(3, 50, 2), $"Unpatched SQLite: {version}");
        command.CommandText = "CREATE TABLE probe (id INTEGER PRIMARY KEY, value TEXT); INSERT INTO probe VALUES (1, 'ok');";
        command.ExecuteNonQuery();
        command.CommandText = "SELECT value FROM probe WHERE id = 1";
        Assert.Equal("ok", command.ExecuteScalar());
    }

    public class SocketProxy : DispatchProxy
    {
        public EventHandler<AudioMediaReceivedEventArgs>? Handler { get; private set; }
        protected override object? Invoke(MethodInfo? method, object?[]? arguments)
        {
            if (method?.Name == "add_AudioMediaReceived") Handler += (EventHandler<AudioMediaReceivedEventArgs>)arguments![0]!;
            else if (method?.Name == "remove_AudioMediaReceived") Handler -= (EventHandler<AudioMediaReceivedEventArgs>)arguments![0]!;
            else throw new NotSupportedException(method?.Name);
            return null;
        }
    }

    private sealed class TestLease : IAudioBufferLease
    {
        private readonly NativePcmSlice[] slices;
        public int DisposeCount { get; private set; }
        public int SliceReads { get; private set; }
        public int? OverrideLength { get; init; }
        public bool MissingSlices { get; init; }
        public bool IsPcm16K { get; init; } = true;
        public bool IsSilence { get; init; }
        public long ReceivedAt { get; }
        public IReadOnlyList<NativePcmSlice>? Slices
        {
            get { SliceReads++; return MissingSlices ? null : slices.Select(s =>
                OverrideLength is { } length ? s with { Length = length } : s).ToArray(); }
        }
        public TestLease(long timestamp, params uint[] sources)
        {
            ReceivedAt = timestamp;
            slices = sources.Select(source =>
            {
                var pointer = Marshal.AllocHGlobal(640);
                Marshal.Copy(Enumerable.Repeat((byte)source, 640).ToArray(), 0, pointer, 640);
                return new NativePcmSlice(source, pointer, 640, 0);
            }).ToArray();
        }
        public void Dispose()
        {
            DisposeCount++;
            if (DisposeCount == 1) foreach (var slice in slices) Marshal.FreeHGlobal(slice.Data);
        }
    }
}
