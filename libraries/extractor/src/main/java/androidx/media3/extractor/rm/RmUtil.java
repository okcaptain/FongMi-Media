package androidx.media3.extractor.rm;

import androidx.media3.common.C;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.TrackOutput;

final class RmUtil {

  /**
   * Number of PCM samples per codec frame for RealAudio codecs that use 1024-sample windows
   * (Cook, ATRAC, raac/racp AAC).
   */
  static final int SAMPLES_PER_CODEC_FRAME = 1024;

  /**
   * Writes {@code size} bytes from {@code data} to {@code output} and immediately appends the
   * corresponding sample metadata.
   *
   * <p>This is the canonical one-shot emission path used by all {@link TrackReader} implementations
   * that emit one complete sample per call.
   */
  static void emitSample(TrackOutput output, ParsableByteArray data, int size, long timestampUs, boolean isKeyFrame) {
    output.sampleData(data, size);
    output.sampleMetadata(timestampUs, isKeyFrame ? C.BUFFER_FLAG_KEY_FRAME : 0, size, 0, null);
  }
}
