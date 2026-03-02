package androidx.media3.extractor.rm;

import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.TrackOutput;

/**
 * AC3 (dnet) audio: RealMedia stores AC3 in big-endian byte order.
 * FFmpeg rm_ac3_swap_bytes swaps every pair of bytes before decoding.
 */
final class Ac3AudioReader implements TrackReader {

  private final TrackOutput trackOutput;

  Ac3AudioReader(TrackOutput trackOutput) {
    this.trackOutput = trackOutput;
  }

  @Override
  public void consume(ParsableByteArray data, int dataSize, long timestampUs, boolean isKeyFrame) {
    byte[] buf = data.getData();
    int start = data.getPosition();
    int end = start + (dataSize & ~1); // round down to even
    for (int i = start; i < end; i += 2) {
      byte tmp = buf[i];
      buf[i] = buf[i + 1];
      buf[i + 1] = tmp;
    }
    RmUtil.emitSample(trackOutput, data, dataSize, timestampUs, isKeyFrame);
  }

  @Override
  public void seek() {
  }
}
