using System.Reflection;
using System.Runtime.InteropServices;
using Microsoft.Graph.Communications.Calls;
using Microsoft.Graph.Models;
using TeamsCapture.Media;
using Xunit;

namespace TeamsCapture.Media.Tests;

public class SdkParticipantSnapshotAdapterTests
{
    private const long T = TimeSpan.TicksPerSecond;

    [Fact]
    public async Task SdkParticipantsMapSeparateSourcesThroughReceiverAndFrameDisposal()
    {
        var map = CreateMap();
        var adapter = new SdkParticipantSnapshotAdapter(map);
        Assert.True(adapter.ApplyFullSnapshot(map.Key, 1, T, [Person("a", "17"), Person("b", "42")]));
        using var receiver = new UnmixedAudioReceiver(map);
        receiver.AllowProcessing(map.Key);
        var lease = new PcmLease(2 * T, 17, 42);
        receiver.Process(lease);
        Assert.True(lease.Disposed);
        await using var reader = receiver.ReadAllAsync().GetAsyncEnumerator();
        Assert.True(await reader.MoveNextAsync().AsTask().WaitAsync(TimeSpan.FromSeconds(5)));
        var first = reader.Current;
        Assert.Equal("a", first.Attribution.Speaker!.ParticipantId);
        Assert.Equal("user-a", first.Attribution.Speaker.UserId);
        Assert.Equal("name-a", first.Attribution.Speaker.DisplayName);
        Assert.Equal((byte)17, first.Pcm.Span[0]);
        Assert.True(await reader.MoveNextAsync().AsTask().WaitAsync(TimeSpan.FromSeconds(5)));
        using var second = reader.Current;
        Assert.Equal("b", second.Attribution.Speaker!.ParticipantId);
        Assert.Equal((byte)42, second.Pcm.Span[0]);
        first.Dispose();
        Assert.All(first.Pcm.ToArray(), value => Assert.Equal(0, value));
    }

    [Theory]
    [InlineData("lobby")]
    [InlineData("missing_lobby")]
    [InlineData("removed")]
    [InlineData("video")]
    [InlineData("receive_only")]
    [InlineData("inactive")]
    public async Task IneligibleSdkSourcesDoNotAssignAPerson(string kind)
    {
        var person = Person("a", "17");
        var resource = person.Resource;
        switch (kind)
        {
            case "lobby": resource.IsInLobby = true; break;
            case "missing_lobby": resource.IsInLobby = null; break;
            case "removed": resource.RemovedState = new RemovedState(); break;
            case "video": resource.MediaStreams![0].MediaType = Modality.Video; break;
            case "receive_only": resource.MediaStreams![0].Direction = MediaDirection.ReceiveOnly; break;
            case "inactive": resource.MediaStreams![0].Direction = MediaDirection.Inactive; break;
        }
        var map = CreateMap();
        Assert.True(new SdkParticipantSnapshotAdapter(map).ApplyFullSnapshot(map.Key, 1, T, [person]));
        using var frame = await Deliver(map, 2 * T);
        Assert.Equal("unknown_source", frame.Attribution.Status);
        Assert.Null(frame.Attribution.Speaker);
    }

    [Theory]
    [InlineData("anonymous")]
    [InlineData("application")]
    [InlineData("name_only")]
    public async Task EndpointWithoutUserIdDoesNotBorrowAPersonName(string kind)
    {
        var person = Person("a", "17");
        person.Resource.Info!.Identity = kind switch
        {
            "application" => new IdentitySet { Application = new Identity { Id = "app", DisplayName = "Someone" } },
            "name_only" => new IdentitySet { User = new Identity { DisplayName = "Someone" } },
            _ => new IdentitySet()
        };
        var map = CreateMap();
        Assert.True(new SdkParticipantSnapshotAdapter(map).ApplyFullSnapshot(map.Key, 1, T, [person]));
        using var frame = await Deliver(map, 2 * T);
        Assert.Equal("a", frame.Attribution.Speaker!.ParticipantId); // Endpoint only.
        Assert.Null(frame.Attribution.Speaker.UserId);
        Assert.Null(frame.Attribution.Speaker.DisplayName);
    }

    [Theory]
    [InlineData("0")]
    [InlineData("-17")]
    [InlineData("+17")]
    [InlineData(" 17")]
    [InlineData("17 ")]
    [InlineData("4294967296")]
    [InlineData("x")]
    [InlineData("")]
    [InlineData(null)]
    public async Task MalformedSourceInvalidatesAnEarlierQueuedAttribution(string? source)
    {
        var map = CreateMap();
        var adapter = new SdkParticipantSnapshotAdapter(map);
        Assert.True(adapter.ApplyFullSnapshot(map.Key, 1, T, [Person("a", "17")]));
        using var receiver = new UnmixedAudioReceiver(map);
        receiver.AllowProcessing(map.Key);
        receiver.Process(new PcmLease(2 * T, 17));
        Assert.False(adapter.ApplyFullSnapshot(map.Key, 2, 3 * T, [Person("a", source)]));
        await using var reader = receiver.ReadAllAsync().GetAsyncEnumerator();
        Assert.True(await reader.MoveNextAsync().AsTask().WaitAsync(TimeSpan.FromSeconds(5)));
        using var frame = reader.Current;
        Assert.Equal("roster_unavailable", frame.Attribution.Status);
        Assert.Null(frame.Attribution.Speaker);
    }

    [Fact]
    public async Task DuplicateOwnershipAndLaterSourceReuseNeverGuessByName()
    {
        var map = CreateMap();
        var adapter = new SdkParticipantSnapshotAdapter(map);
        var first = Person("a", "17");
        var second = Person("b", "17");
        second.Resource.Info!.Identity!.User!.DisplayName = first.Resource.Info!.Identity!.User!.DisplayName;
        Assert.True(adapter.ApplyFullSnapshot(map.Key, 1, T, [first, second]));
        using var duplicate = await Deliver(map, 2 * T);
        Assert.Null(duplicate.Attribution.Speaker);

        map = CreateMap();
        adapter = new SdkParticipantSnapshotAdapter(map);
        Assert.True(adapter.ApplyFullSnapshot(map.Key, 1, T, [first]));
        using var receiver = new UnmixedAudioReceiver(map);
        receiver.AllowProcessing(map.Key);
        receiver.Process(new PcmLease(2 * T, 17));
        Assert.True(adapter.ApplyFullSnapshot(map.Key, 2, 3 * T, [second]));
        await using var reader = receiver.ReadAllAsync().GetAsyncEnumerator();
        Assert.True(await reader.MoveNextAsync().AsTask().WaitAsync(TimeSpan.FromSeconds(5)));
        using var queued = reader.Current;
        Assert.Equal("source_reused", queued.Attribution.Status);
        Assert.Null(queued.Attribution.Speaker);
    }

    [Theory]
    [InlineData("null_direction")]
    [InlineData("unknown_direction")]
    [InlineData("null_type")]
    [InlineData("unknown_type")]
    public async Task UncertainSecondOwnerCannotBeDiscardedToKeepAnEarlierName(string kind)
    {
        var map = CreateMap();
        var adapter = new SdkParticipantSnapshotAdapter(map);
        var first = Person("a", "17");
        Assert.True(adapter.ApplyFullSnapshot(map.Key, 1, T, [first]));
        using var receiver = new UnmixedAudioReceiver(map);
        receiver.AllowProcessing(map.Key);
        receiver.Process(new PcmLease(2 * T, 17));
        var second = Person("b", "17");
        var stream = second.Resource.MediaStreams![0];
        switch (kind)
        {
            case "null_direction": stream.Direction = null; break;
            case "unknown_direction": stream.Direction = (MediaDirection)999; break;
            case "null_type": stream.MediaType = null; break;
            case "unknown_type": stream.MediaType = (Modality)999; break;
        }
        Assert.False(adapter.ApplyFullSnapshot(map.Key, 2, 3 * T, [first, second]));
        // A later apparently clean snapshot cannot erase the uncertainty.
        Assert.False(adapter.ApplyFullSnapshot(map.Key, 3, 4 * T, [first]));
        await using var reader = receiver.ReadAllAsync().GetAsyncEnumerator();
        Assert.True(await reader.MoveNextAsync().AsTask().WaitAsync(TimeSpan.FromSeconds(5)));
        using var frame = reader.Current;
        Assert.Equal("roster_unavailable", frame.Attribution.Status);
        Assert.Null(frame.Attribution.Speaker);
    }

    [Theory]
    [InlineData("tenant")]
    [InlineData("meeting")]
    [InlineData("call")]
    [InlineData("session")]
    public async Task ForeignScopeCannotChangeTheCurrentCall(string field)
    {
        var map = CreateMap();
        var adapter = new SdkParticipantSnapshotAdapter(map);
        Assert.True(adapter.ApplyFullSnapshot(map.Key, 1, T, [Person("a", "17")]));
        var foreign = field switch
        {
            "tenant" => map.Key with { TenantId = Guid.NewGuid() },
            "meeting" => map.Key with { MeetingId = Guid.NewGuid() },
            "call" => map.Key with { CallId = "other" },
            _ => map.Key with { MediaSessionId = Guid.NewGuid() }
        };
        Assert.False(adapter.ApplyFullSnapshot(foreign, 2, 3 * T, null));
        using var frame = await Deliver(map, 2 * T);
        Assert.Equal("a", frame.Attribution.Speaker!.ParticipantId);
    }

    [Theory]
    [InlineData("null_list")]
    [InlineData("null_participant")]
    [InlineData("null_resource")]
    [InlineData("wrong_resource")]
    [InlineData("null_streams")]
    [InlineData("null_stream")]
    [InlineData("duplicate_participant")]
    [InlineData("too_many_participants")]
    [InlineData("too_many_streams")]
    public void InvalidSdkSnapshotCannotPreserveOldNames(string kind)
    {
        var map = CreateMap();
        var adapter = new SdkParticipantSnapshotAdapter(map);
        Assert.True(adapter.ApplyFullSnapshot(map.Key, 1, T, [Person("a", "17")]));
        var first = Person("a", "17");
        var second = Person("b", "42");
        IReadOnlyList<IParticipant>? snapshot = [first];
        switch (kind)
        {
            case "null_list": snapshot = null; break;
            case "null_participant": snapshot = [null!]; break;
            case "null_resource": ((ParticipantProxy)first).Value = null; break;
            case "wrong_resource": ((ParticipantProxy)first).WrapperId = "other"; break;
            case "null_streams": first.Resource.MediaStreams = null; break;
            case "null_stream": first.Resource.MediaStreams = [null!]; break;
            case "duplicate_participant": snapshot = [first, first]; break;
            case "too_many_participants": snapshot = Enumerable.Repeat(first, 1001).ToArray(); break;
            case "too_many_streams":
                first.Resource.MediaStreams = Enumerable.Range(0, 501).Select(_ => new MediaStream { MediaType = Modality.Video, Direction = MediaDirection.SendOnly }).ToList();
                second.Resource.MediaStreams = Enumerable.Range(0, 500).Select(_ => new MediaStream { MediaType = Modality.Video, Direction = MediaDirection.SendOnly }).ToList();
                snapshot = [first, second]; break;
        }
        Assert.False(adapter.ApplyFullSnapshot(map.Key, 2, 3 * T, snapshot));
        Assert.Equal("roster_unavailable", map.Resolve(map.Key, 17, 2 * T, T / 50).Status);
    }

    [Fact]
    public async Task CompleteSnapshotRemovalAndExpiryDoNotRelabelCachedRoster()
    {
        var map = CreateMap();
        var adapter = new SdkParticipantSnapshotAdapter(map);
        Assert.True(adapter.ApplyFullSnapshot(map.Key, 1, T, [Person("a", "17")]));
        using (var expired = await Deliver(map, 8 * T))
            Assert.Equal("roster_expired", expired.Attribution.Status);
        map = CreateMap();
        adapter = new SdkParticipantSnapshotAdapter(map);
        Assert.True(adapter.ApplyFullSnapshot(map.Key, 1, T, [Person("a", "17")]));
        Assert.True(adapter.ApplyFullSnapshot(map.Key, 2, 2 * T, []));
        using var removed = await Deliver(map, 3 * T);
        Assert.Equal("unknown_source", removed.Attribution.Status);
    }

    [Theory]
    [InlineData(1, 2)]
    [InlineData(2, 1)]
    [InlineData(0, 2)]
    [InlineData(2, -1)]
    public void InvalidObservationOrderingInvalidatesMap(long revision, long seconds)
    {
        var map = CreateMap();
        var adapter = new SdkParticipantSnapshotAdapter(map);
        Assert.True(adapter.ApplyFullSnapshot(map.Key, 1, T, [Person("a", "17")]));
        Assert.False(adapter.ApplyFullSnapshot(map.Key, revision, seconds * T, [Person("a", "17")]));
        Assert.Equal("roster_unavailable", map.Resolve(map.Key, 17, 2 * T, T / 50).Status);
    }

    private static TemporalSpeakerMap CreateMap() => new(TemporalSpeakerMapTests.Key(), TimeSpan.FromSeconds(5));

    private static IParticipant Person(string id, string? source)
    {
        var sdk = DispatchProxy.Create<IParticipant, ParticipantProxy>();
        var proxy = (ParticipantProxy)sdk;
        proxy.WrapperId = id;
        proxy.Value = new Participant
        {
            Id = id, IsInLobby = false,
            Info = new ParticipantInfo { Identity = new IdentitySet { User = new Identity { Id = "user-" + id, DisplayName = "name-" + id } } },
            MediaStreams = [new MediaStream { MediaType = Modality.Audio, Direction = MediaDirection.SendReceive, SourceId = source }]
        };
        return sdk;
    }

    private static async Task<OwnedAudioFrame> Deliver(TemporalSpeakerMap map, long at)
    {
        using var receiver = new UnmixedAudioReceiver(map);
        receiver.AllowProcessing(map.Key);
        receiver.Process(new PcmLease(at, 17));
        await using var reader = receiver.ReadAllAsync().GetAsyncEnumerator();
        Assert.True(await reader.MoveNextAsync().AsTask().WaitAsync(TimeSpan.FromSeconds(5)));
        return reader.Current;
    }

    public class ParticipantProxy : DispatchProxy
    {
        public Participant? Value { get; set; }
        public string? WrapperId { get; set; }
        protected override object? Invoke(MethodInfo? method, object?[]? arguments) => method?.Name switch
        {
            "get_Resource" => Value, "get_Id" => WrapperId,
            _ => throw new NotSupportedException(method?.Name)
        };
    }

    private sealed class PcmLease : IAudioBufferLease
    {
        public bool IsPcm16K => true;
        public bool IsSilence => false;
        public long ReceivedAt { get; }
        public IReadOnlyList<NativePcmSlice>? Slices { get; }
        public bool Disposed { get; private set; }
        public PcmLease(long at, params uint[] sources)
        {
            ReceivedAt = at;
            Slices = sources.Select(source =>
            {
                var pointer = Marshal.AllocHGlobal(640);
                Marshal.Copy(Enumerable.Repeat((byte)source, 640).ToArray(), 0, pointer, 640);
                return new NativePcmSlice(source, pointer, 640, 0);
            }).ToArray();
        }
        public void Dispose()
        {
            if (Disposed) return;
            Disposed = true;
            foreach (var slice in Slices!) Marshal.FreeHGlobal(slice.Data);
        }
    }
}
