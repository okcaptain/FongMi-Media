package androidx.media3.extractor.asf;

import androidx.annotation.NonNull;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.SeekPoint;

/**
 * A {@link SeekMap} for ASF files with fixed-size data packets, using linear interpolation.
 */
final class AsfSeekMap implements SeekMap {

  private final long durationUs;
  private final long firstPacketPosition;
  private final long packetSize;
  private final long packetCount;

  AsfSeekMap(long durationUs, long firstPacketPosition, long packetSize, long packetCount) {
    this.durationUs = durationUs;
    this.firstPacketPosition = firstPacketPosition;
    this.packetSize = packetSize;
    this.packetCount = packetCount;
  }

  @Override
  public boolean isSeekable() {
    return true;
  }

  @Override
  public long getDurationUs() {
    return durationUs;
  }

  @NonNull
  @Override
  public SeekPoints getSeekPoints(long timeUs) {
    if (packetCount <= 0 || durationUs <= 0) {
      return new SeekPoints(SeekPoint.START);
    }
    long clampedTimeUs = Math.max(0, Math.min(timeUs, durationUs));
    double fraction = (double) clampedTimeUs / (double) durationUs;
    long packetIndex = (long) (fraction * packetCount);
    packetIndex = Math.max(0, Math.min(packetIndex, packetCount - 1));
    SeekPoint earlier = makeSeekPoint(packetIndex);
    if (packetIndex + 1 < packetCount) {
      return new SeekPoints(earlier, makeSeekPoint(packetIndex + 1));
    }
    return new SeekPoints(earlier);
  }

  private SeekPoint makeSeekPoint(long packetIndex) {
    long byteOffset = firstPacketPosition + packetIndex * packetSize;
    long approxTimeUs = (long) ((double) packetIndex / (double) packetCount * durationUs);
    return new SeekPoint(approxTimeUs, byteOffset);
  }
}
