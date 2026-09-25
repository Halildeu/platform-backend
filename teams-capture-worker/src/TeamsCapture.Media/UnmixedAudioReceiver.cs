using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Threading.Channels;
using Microsoft.Skype.Bots.Media;

[assembly: InternalsVisibleTo("TeamsCapture.Media.Tests")]

namespace TeamsCapture.Media;

/// <summary>One owned PCM16/16kHz/mono fragment. Consumer must dispose after use.</summary>
public sealed class OwnedAudioFrame : IDisposable
{
    private readonly byte[] bytes;
    public MediaCallKey Call { get; }
    public uint SourceId { get; }
    public long ReceivedAt { get; }
    public long OriginalSenderTimestamp { get; }
    public SpeakerAttribution Attribution { get; private set; } = new("not_delivered");
    public ReadOnlyMemory<byte> Pcm => bytes;

    internal OwnedAudioFrame(MediaCallKey call, uint sourceId, long receivedAt, long originalTimestamp,
        byte[] bytes)
    {
        Call = call;
        SourceId = sourceId;
        ReceivedAt = receivedAt;
        OriginalSenderTimestamp = originalTimestamp;
        this.bytes = bytes;
    }

    public void Dispose() => CryptographicOperations.ZeroMemory(bytes);

    // Resolve at transfer, so roster invalidation or source reuse discovered
    // while this frame was queued cannot leave a previously assigned name.
    internal void ResolveAtDelivery(TemporalSpeakerMap speakers) =>
        Attribution = speakers.Resolve(Call, SourceId, ReceivedAt,
            bytes.Length * TimeSpan.TicksPerSecond / 32000L);
}

/// <summary>
/// SDK event adapter, not a bot host. Copies unmixed buffers while their native
/// owner is alive, then disposes it. It never reads mixed Data/ActiveSpeakers to
/// invent a speaker. No network/disk I/O runs on the SDK callback thread.
/// </summary>
public sealed class UnmixedAudioReceiver : IDisposable
{
    private const int MaxFrameBytes = 6400; // At most 200 ms at PCM16/16kHz/mono.
    private readonly object sync = new();
    private readonly TemporalSpeakerMap speakers;
    private readonly Channel<OwnedAudioFrame> frames;
    private IAudioSocket? socket;
    private bool processingAllowed;
    private bool stopped;
    private long lastReceivedAt = -1;
    public string? Failure { get; private set; }

    public UnmixedAudioReceiver(TemporalSpeakerMap speakers, int queueCapacity = 256)
    {
        ArgumentNullException.ThrowIfNull(speakers);
        if (queueCapacity is < 1 or > 1024) throw new ArgumentOutOfRangeException(nameof(queueCapacity));
        this.speakers = speakers;
        frames = Channel.CreateBounded<OwnedAudioFrame>(new BoundedChannelOptions(queueCapacity)
        {
            FullMode = BoundedChannelFullMode.Wait,
            AllowSynchronousContinuations = false
        });
    }

    public static AudioSocketSettings CreateSocketSettings() => new()
    {
        StreamDirections = StreamDirection.Recvonly,
        SupportedAudioFormat = AudioFormat.Pcm16K,
        ReceiveUnmixedMeetingAudio = true
    };

    public void Attach(MediaCallKey key, IAudioSocket audioSocket)
    {
        ArgumentNullException.ThrowIfNull(audioSocket);
        lock (sync)
        {
            if (key != speakers.Key || stopped || socket is not null)
                throw new InvalidOperationException("Receiver is not attachable to this media session.");
            socket = audioSocket;
            socket.AudioMediaReceived += OnAudioMediaReceived;
        }
    }

    /// <summary>
    /// Trusted host calls only after current recording permission, consent and
    /// Microsoft recording-status confirmation for this exact call. No public
    /// HTTP switch is provided. Revocation is terminal; re-authorize with a new
    /// receiver/session, never replay queued audio across a permission gap.
    /// </summary>
    public void AllowProcessing(MediaCallKey key)
    {
        lock (sync)
        {
            if (key != speakers.Key || stopped) throw new InvalidOperationException("Invalid media session.");
            processingAllowed = true;
        }
    }

    public void RevokeProcessing() { lock (sync) Stop("processing_revoked"); }

    public async IAsyncEnumerable<OwnedAudioFrame> ReadAllAsync(
        [EnumeratorCancellation] CancellationToken cancellationToken = default)
    {
        while (await frames.Reader.WaitToReadAsync(cancellationToken).ConfigureAwait(false))
        {
            OwnedAudioFrame? frame;
            lock (sync)
            {
                if (!frames.Reader.TryRead(out frame)) continue;
                if (!processingAllowed || stopped) { frame.Dispose(); continue; }
                frame.ResolveAtDelivery(speakers);
            }
            // Already transferred frames belong to the consumer; it must also
            // check current authorization immediately before downstream I/O.
            yield return frame;
        }
    }

    private void OnAudioMediaReceived(object? sender, AudioMediaReceivedEventArgs args)
    {
        if (args.Buffer is null) { lock (sync) Stop("missing_native_buffer"); return; }
        Process(new SdkBufferLease(args.Buffer));
    }

    internal void Process(IAudioBufferLease buffer)
    {
        using (buffer)
        {
            lock (sync)
            {
                if (stopped || !processingAllowed) return;
                try
                {
                    if (!buffer.IsPcm16K || buffer.ReceivedAt < 0 || buffer.ReceivedAt <= lastReceivedAt)
                    { Stop("invalid_audio_clock_or_format"); return; }
                    var slices = buffer.Slices;
                    if (slices is null && buffer.IsSilence) slices = [];
                    // Missing unmixed data is not silence and must not fall back to mixed audio.
                    if (slices is null || slices.Count > 4) { Stop("unmixed_audio_unavailable"); return; }
                    var seen = new HashSet<uint>();
                    foreach (var slice in slices)
                    {
                        if (slice.SourceId == 0 || !seen.Add(slice.SourceId) || slice.Data == IntPtr.Zero
                            || slice.Length is <= 0 or > MaxFrameBytes || slice.Length % 2 != 0)
                        { Stop("invalid_unmixed_frame"); return; }
                    }
                    lastReceivedAt = buffer.ReceivedAt;
                    foreach (var slice in slices)
                    {
                        var bytes = new byte[slice.Length];
                        try { Marshal.Copy(slice.Data, bytes, 0, bytes.Length); }
                        catch { CryptographicOperations.ZeroMemory(bytes); throw; }
                        var frame = new OwnedAudioFrame(speakers.Key, slice.SourceId, buffer.ReceivedAt,
                            slice.OriginalTimestamp, bytes);
                        if (!frames.Writer.TryWrite(frame))
                        {
                            frame.Dispose();
                            Stop("audio_backpressure");
                            return;
                        }
                    }
                }
                catch (Exception error) when (error is ArgumentException or InvalidOperationException
                    or OverflowException)
                { Stop("invalid_native_buffer"); }
            }
        }
    }

    public void Dispose() { lock (sync) Stop(null); }

    private void Stop(string? failure)
    {
        if (stopped) return;
        Failure = failure;
        stopped = true;
        processingAllowed = false;
        if (socket is not null) socket.AudioMediaReceived -= OnAudioMediaReceived;
        socket = null;
        frames.Writer.TryComplete();
        while (frames.Reader.TryRead(out var frame)) frame.Dispose();
        speakers.Clear();
    }
}

internal readonly record struct NativePcmSlice(uint SourceId, IntPtr Data, int Length, long OriginalTimestamp);
internal interface IAudioBufferLease : IDisposable
{
    bool IsPcm16K { get; }
    bool IsSilence { get; }
    long ReceivedAt { get; }
    IReadOnlyList<NativePcmSlice>? Slices { get; }
}

internal sealed class SdkBufferLease(AudioMediaBuffer buffer) : IAudioBufferLease
{
    public bool IsPcm16K => buffer.AudioFormat == AudioFormat.Pcm16K;
    public bool IsSilence => buffer.IsSilence;
    public long ReceivedAt => buffer.Timestamp;
    public IReadOnlyList<NativePcmSlice>? Slices => buffer.UnmixedAudioBuffers?.Select(slice =>
        new NativePcmSlice(slice.ActiveSpeakerId, slice.Data, checked((int)slice.Length),
            slice.OriginalSenderTimestamp)).ToArray();
    public void Dispose() => buffer.Dispose();
}
