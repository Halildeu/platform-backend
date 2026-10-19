using System.Globalization;
using Microsoft.Graph.Communications.Calls;
using Microsoft.Graph.Models;

namespace TeamsCapture.Media;

/// <summary>
/// Projects a fresh, complete SDK roster into the receiving-clock speaker map.
/// The native host must serialize SDK roster observation and supply its actual
/// media-clock timestamp. Delta lists, cached REST rosters and polling timers
/// must not be passed as new observations. This is not authentication or a host.
/// </summary>
public sealed class SdkParticipantSnapshotAdapter(TemporalSpeakerMap speakers)
{
    private const int MaxParticipants = 1000;
    private const int MaxStreams = 1000;
    private readonly TemporalSpeakerMap speakers = speakers ?? throw new ArgumentNullException(nameof(speakers));

    public bool ApplyFullSnapshot(MediaCallKey key, long revision, long observedAt,
        IReadOnlyList<IParticipant>? participants)
    {
        // A foreign call must neither relabel nor invalidate the legitimate call.
        if (key != speakers.Key) return false;
        if (participants is null || participants.Count > MaxParticipants) return Invalidate();
        var projected = new List<ParticipantAudioSources>(participants.Count);
        var streamCount = 0;
        try
        {
            foreach (var sdkParticipant in participants)
            {
                var resource = sdkParticipant?.Resource;
                if (resource is null || !MediaCallKey.ValidText(resource.Id, 256)
                    || sdkParticipant!.Id != resource.Id || resource.MediaStreams is null)
                    return Invalidate();

                var user = resource.Info?.Identity?.User;
                if (user?.Id is not null && !MediaCallKey.ValidText(user.Id, 256)
                    || user?.DisplayName is not null && !MediaCallKey.ValidText(user.DisplayName, 256))
                    return Invalidate();
                // Only the user identity supplies a person's name. Application,
                // device and anonymous endpoints never borrow a displayed name.
                var identity = new SpeakerIdentity(resource.Id!, user?.Id,
                    user?.Id is null ? null : user.DisplayName);
                var sources = new List<uint>();
                foreach (var stream in resource.MediaStreams)
                {
                    if (++streamCount > MaxStreams || stream is null) return Invalidate();
                    // Unknown metadata can hide a second owner of an otherwise
                    // valid audio source. Never discard that conflicting evidence.
                    if (stream.MediaType is not (Modality.Audio or Modality.Video
                        or Modality.VideoBasedScreenSharing or Modality.Data)
                        || stream.Direction is not (MediaDirection.SendOnly or MediaDirection.SendReceive
                            or MediaDirection.ReceiveOnly or MediaDirection.Inactive)) return Invalidate();
                    if (stream.MediaType != Modality.Audio) continue;
                    if (stream.Direction is not (MediaDirection.SendOnly or MediaDirection.SendReceive)) continue;
                    var sourceText = stream.SourceId;
                    if (sourceText is null || sourceText.Length is < 1 or > 10
                        || sourceText.Any(c => c is < '0' or > '9')
                        || !uint.TryParse(sourceText, NumberStyles.None, CultureInfo.InvariantCulture, out var source)
                        || source == 0) return Invalidate();
                    sources.Add(source);
                }
                // Retain excluded ownership claims to detect conflicts/reuse,
                // but never attribute lobby, removed or unconfirmed participants.
                projected.Add(new(identity, sources,
                    resource.IsInLobby != false || resource.RemovedState is not null));
            }
        }
        catch (Exception error) when (error is InvalidOperationException or ArgumentException)
        {
            // SDK collection/resource changed during projection: no stale fallback.
            return Invalidate();
        }
        return speakers.ApplySnapshot(key, revision, observedAt, projected);
    }

    private bool Invalidate()
    {
        speakers.Clear();
        return false;
    }
}
