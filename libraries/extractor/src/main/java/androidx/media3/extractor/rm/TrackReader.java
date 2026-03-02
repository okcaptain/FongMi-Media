package androidx.media3.extractor.rm;

import androidx.media3.common.util.ParsableByteArray;

interface TrackReader {

  void consume(ParsableByteArray data, int dataSize, long timestampUs, boolean isKeyFrame);

  void seek();
}
