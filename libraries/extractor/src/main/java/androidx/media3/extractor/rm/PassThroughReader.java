package androidx.media3.extractor.rm;

import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.TrackOutput;

/**
 * Forwards raw packets unchanged to the track output.
 */
final class PassThroughReader implements TrackReader {

  private final TrackOutput trackOutput;

  PassThroughReader(TrackOutput trackOutput) {
    this.trackOutput = trackOutput;
  }

  @Override
  public void consume(ParsableByteArray data, int dataSize, long timestampUs, boolean isKeyFrame) {
    RmUtil.emitSample(trackOutput, data, dataSize, timestampUs, isKeyFrame);
  }

  @Override
  public void seek() {
  }
}
