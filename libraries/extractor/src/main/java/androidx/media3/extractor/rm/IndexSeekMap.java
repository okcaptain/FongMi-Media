package androidx.media3.extractor.rm;

import androidx.annotation.NonNull;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.SeekPoint;
import java.util.List;

/**
 * Binary-search seek map built from the INDX chunk.
 */
final class IndexSeekMap implements SeekMap {

  private final long durationUs;
  private final List<SeekPoint> index;

  IndexSeekMap(long durationUs, List<SeekPoint> index) {
    this.durationUs = durationUs;
    this.index = index;
  }

  @Override
  public boolean isSeekable() {
    return !index.isEmpty();
  }

  @Override
  public long getDurationUs() {
    return durationUs;
  }

  @NonNull
  @Override
  public SeekPoints getSeekPoints(long timeUs) {
    if (index.isEmpty()) {
      return new SeekPoints(SeekPoint.START);
    }

    int lo = 0, hi = index.size() - 1;
    while (lo < hi) {
      int mid = (lo + hi + 1) / 2;
      if (index.get(mid).timeUs <= timeUs) {
        lo = mid;
      } else {
        hi = mid - 1;
      }
    }
    SeekPoint before = index.get(lo);
    if (lo + 1 < index.size()) {
      return new SeekPoints(before, index.get(lo + 1));
    }
    return new SeekPoints(before);
  }
}
