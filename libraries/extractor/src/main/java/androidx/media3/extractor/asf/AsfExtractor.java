package androidx.media3.extractor.asf;

import static com.google.common.base.Preconditions.checkNotNull;

import android.util.SparseArray;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.TrackOutput;
import java.io.EOFException;
import java.io.IOException;
import java.util.Arrays;

/**
 * Extracts data from ASF (Advanced Systems Format) containers.
 *
 * <p>Supports WMA (v1/v2, Pro, Lossless, Voice) and WMV/VC-1 video, including multiple streams,
 * audio descrambling, ECC resync, and DVR-MS payload extensions.
 */
public final class AsfExtractor implements Extractor {

  @Nullable
  private ExtractorOutput extractorOutput;
  @Nullable
  private AsfPacketReader packetReader;
  private boolean headerParsed;

  private final byte[] sniffBuffer = new byte[16];

  @Override
  public boolean sniff(ExtractorInput input) throws IOException {
    try {
      input.peekFully(sniffBuffer, 0, 16, false);
      return Arrays.equals(sniffBuffer, AsfGuid.HEADER_OBJECT);
    } catch (EOFException e) {
      return false;
    }
  }

  @Override
  public void init(@NonNull ExtractorOutput output) {
    this.extractorOutput = output;
  }

  @Override
  public @ReadResult int read(@NonNull ExtractorInput input, @NonNull PositionHolder seekPosition) throws IOException {
    if (!headerParsed) {
      AsfHeaderReader header = new AsfHeaderReader();
      header.read(input);
      packetReader = buildPacketReader(header, checkNotNull(extractorOutput));
      headerParsed = true;
    }
    return checkNotNull(packetReader).read(input) ? RESULT_CONTINUE : RESULT_END_OF_INPUT;
  }

  @Override
  public void seek(long position, long timeUs) {
    if (packetReader != null) {
      packetReader.reset();
    }
  }

  @Override
  public void release() {
  }

  private AsfPacketReader buildPacketReader(AsfHeaderReader header, ExtractorOutput output) {
    SparseArray<TrackOutput> trackOutputs = new SparseArray<>();
    for (int i = 0; i < header.audioStreams().size(); i++) {
      AudioStreamInfo audioInfo = header.audioStreams().valueAt(i);
      TrackOutput to = registerAudioTrack(output, audioInfo);
      trackOutputs.put(audioInfo.streamNumber, to);
    }
    for (int i = 0; i < header.videoStreams().size(); i++) {
      VideoStreamInfo videoInfo = header.videoStreams().valueAt(i);
      TrackOutput to = registerVideoTrack(output, videoInfo);
      trackOutputs.put(videoInfo.streamNumber, to);
    }
    output.seekMap(buildSeekMap(header));
    output.endTracks();
    return new AsfPacketReader(header.audioStreams(), header.videoStreams(), header.payloadExtensions(), trackOutputs, header.packetSize(), header.prerollMs());
  }

  private static TrackOutput registerAudioTrack(ExtractorOutput output, AudioStreamInfo audioInfo) {
    TrackOutput to = output.track(audioInfo.streamNumber, C.TRACK_TYPE_AUDIO);
    to.format(AsfUtil.buildAudioFormat(audioInfo));
    return to;
  }

  private static TrackOutput registerVideoTrack(ExtractorOutput output, VideoStreamInfo videoInfo) {
    TrackOutput to = output.track(videoInfo.streamNumber, C.TRACK_TYPE_VIDEO);
    to.format(AsfUtil.buildVideoFormat(videoInfo, 1f));
    return to;
  }

  private static SeekMap buildSeekMap(AsfHeaderReader header) {
    if (!header.isBroadcast() && header.durationUs() != C.TIME_UNSET && header.packetSize() > 0 && header.packetCount() > 0 && header.firstPacketPosition() > 0) {
      return new AsfSeekMap(header.durationUs(), header.firstPacketPosition(), header.packetSize(), header.packetCount());
    }
    return new SeekMap.Unseekable(header.durationUs());
  }
}
