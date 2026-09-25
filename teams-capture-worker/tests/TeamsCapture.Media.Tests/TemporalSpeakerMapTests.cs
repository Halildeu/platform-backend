using TeamsCapture.Media;
using Xunit;

namespace TeamsCapture.Media.Tests;

public class TemporalSpeakerMapTests
{
    private const long T = TimeSpan.TicksPerSecond;
    internal static MediaCallKey Key() => new(Guid.NewGuid(), Guid.NewGuid(), "call-123", Guid.NewGuid());
    internal static ParticipantAudioSources Person(string id, string name, params uint[] sources) =>
        new(new SpeakerIdentity(id, "user-" + id, name), sources);

    [Fact]
    public void SimultaneousSpeakersUseSourceIdentityEvenWithIdenticalNames()
    {
        var map = new TemporalSpeakerMap(Key(), TimeSpan.FromSeconds(5));
        Assert.True(map.ApplySnapshot(map.Key, 1, T, [Person("a", "Mehmet", 17), Person("b", "Mehmet", 42)]));
        Assert.Equal("a", map.Resolve(map.Key, 17, 2 * T, T / 50).Speaker!.ParticipantId);
        Assert.Equal("b", map.Resolve(map.Key, 42, 2 * T, T / 50).Speaker!.ParticipantId);
        Assert.Null(map.Resolve(map.Key, 99, 2 * T, T / 50).Speaker);
    }

    [Fact]
    public void DelayedFramesNeverUseFutureRosterAndCrossingChangeIsUnknown()
    {
        var map = new TemporalSpeakerMap(Key(), TimeSpan.FromSeconds(5));
        map.ApplySnapshot(map.Key, 1, T, [Person("a", "Zeynep", 17)]);
        map.ApplySnapshot(map.Key, 2, 2 * T, [Person("a", "Zeynep", 18)]);
        Assert.Equal("a", map.Resolve(map.Key, 17, T + T / 2, T / 50).Speaker!.ParticipantId);
        Assert.Null(map.Resolve(map.Key, 18, T + T / 2, T / 50).Speaker);
        Assert.Equal("roster_transition", map.Resolve(map.Key, 17, 2 * T - 10, T / 50).Status);
        Assert.Null(map.Resolve(map.Key, 17, 2 * T, T / 50).Speaker);
        Assert.Equal("a", map.Resolve(map.Key, 18, 2 * T, T / 50).Speaker!.ParticipantId);
    }

    [Fact]
    public void ReusedSourceNeverTransfersPreviousPersonsAudioToNewParticipant()
    {
        var map = new TemporalSpeakerMap(Key(), TimeSpan.FromSeconds(5));
        map.ApplySnapshot(map.Key, 1, T, [Person("a", "Zeynep", 17)]);
        map.ApplySnapshot(map.Key, 2, 2 * T, []);
        map.ApplySnapshot(map.Key, 3, 3 * T, [Person("b", "Mehmet", 17)]);
        Assert.Equal("source_reused", map.Resolve(map.Key, 17, 3 * T, T / 50).Status);
        Assert.Null(map.Resolve(map.Key, 17, T + T / 2, T / 50).Speaker);
    }

    [Fact]
    public void UnknownMutedOrLobbyMetadataDoesNotInventAnOwner()
    {
        var map = new TemporalSpeakerMap(Key(), TimeSpan.FromSeconds(5));
        map.ApplySnapshot(map.Key, 1, T, [Person("a", "Mehmet", 17) with { IsInLobby = true }]);
        Assert.Null(map.Resolve(map.Key, 17, 2 * T, T / 50).Speaker);
        Assert.Null(map.Resolve(map.Key, 0, 2 * T, T / 50).Speaker);
    }

    [Fact]
    public void ForeignCallOrTenantOrSessionCannotSupplyRosterOrResolveIdentity()
    {
        var key = Key();
        var map = new TemporalSpeakerMap(key, TimeSpan.FromSeconds(5));
        foreach (var foreign in new[] {key with { TenantId = Guid.NewGuid() },
            key with { MeetingId = Guid.NewGuid() }, key with { CallId = "other" },
            key with { MediaSessionId = Guid.NewGuid() }})
        {
            Assert.False(map.ApplySnapshot(foreign, 1, T, [Person("a", "Mehmet", 17)]));
            Assert.Equal("wrong_session", map.Resolve(foreign, 17, 2 * T, T / 50).Status);
        }
        Assert.True(map.ApplySnapshot(key, 1, T, [Person("a", "Mehmet", 17)]));
    }

    [Theory]
    [InlineData(1, 2)]
    [InlineData(2, 1)]
    public void OutOfOrderObservationInvalidatesInsteadOfReusingStaleIdentity(int revision, int seconds)
    {
        var map = new TemporalSpeakerMap(Key(), TimeSpan.FromSeconds(5));
        map.ApplySnapshot(map.Key, 1, T, [Person("a", "Mehmet", 17)]);
        Assert.False(map.ApplySnapshot(map.Key, revision, seconds * T, [Person("b", "Zeynep", 17)]));
        Assert.Null(map.Resolve(map.Key, 17, 3 * T, T / 50).Speaker);
        Assert.False(map.ApplySnapshot(map.Key, 9, 9 * T, [Person("a", "Mehmet", 17)]));
    }

    [Fact]
    public void StaleRosterOrUnavailableHistoryCannotNameAudio()
    {
        var map = new TemporalSpeakerMap(Key(), TimeSpan.FromSeconds(5));
        map.ApplySnapshot(map.Key, 1, T, [Person("a", "Mehmet", 17)]);
        Assert.Equal("roster_unavailable", map.Resolve(map.Key, 17, 0, T / 50).Status);
        Assert.Equal("roster_expired", map.Resolve(map.Key, 17, 6 * T, T / 50).Status);
        for (var i = 2; i <= 150; i++) map.ApplySnapshot(map.Key, i, i * T, [Person("a", "Mehmet", 17)]);
        Assert.Equal("roster_unavailable", map.Resolve(map.Key, 17, T, T / 50).Status);
    }

    [Fact]
    public void InputCollectionsCannotChangePreviouslyObservedMapping()
    {
        var map = new TemporalSpeakerMap(Key(), TimeSpan.FromSeconds(5));
        uint[] sources = [17];
        ParticipantAudioSources[] participants = [Person("a", "Mehmet", sources)];
        map.ApplySnapshot(map.Key, 1, T, participants);
        sources[0] = 42;
        participants[0] = Person("b", "Zeynep", 17);
        Assert.Equal("a", map.Resolve(map.Key, 17, 2 * T, T / 50).Speaker!.ParticipantId);
        Assert.Null(map.Resolve(map.Key, 42, 2 * T, T / 50).Speaker);
    }

    [Fact]
    public void ConflictingSourceIsUnknownAndCapacityFailureClearsPreviousRoster()
    {
        var map = new TemporalSpeakerMap(Key(), TimeSpan.FromSeconds(5));
        map.ApplySnapshot(map.Key, 1, T, [Person("a", "Mehmet", 17), Person("b", "Zeynep", 17)]);
        Assert.Null(map.Resolve(map.Key, 17, 2 * T, T / 50).Speaker);
        var excessive = Enumerable.Range(1, 1001).Select(i => Person(i.ToString(), "Name", (uint)i)).ToArray();
        Assert.False(map.ApplySnapshot(map.Key, 2, 2 * T, excessive));
        Assert.Equal("roster_unavailable", map.Resolve(map.Key, 17, 2 * T, T / 50).Status);
    }
}
