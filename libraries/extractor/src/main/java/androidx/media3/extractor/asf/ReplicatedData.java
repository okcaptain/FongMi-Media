package androidx.media3.extractor.asf;

/**
 * Decoded content of an ASF replicated data block in a payload header.
 */
final class ReplicatedData {

  static final long ABSENT = -1L; // field not present / unreadable

  final long objSize;        // declared media-object size in bytes, or ABSENT
  final long presentationMs; // presentation timestamp in ms, or ABSENT
  final boolean tsIsPts;     // true when presentationMs is from DVR-MS ext (0x2A); preroll excluded
  final long timeDelta;      // compressed multi-payload (repLen==1): per-sub-payload delta in ms; else ABSENT

  ReplicatedData(long objSize, long presentationMs, boolean tsIsPts, long timeDelta) {
    this.objSize = objSize;
    this.presentationMs = presentationMs;
    this.tsIsPts = tsIsPts;
    this.timeDelta = timeDelta;
  }

  static ReplicatedData absent() {
    return new ReplicatedData(ABSENT, ABSENT, false, ABSENT);
  }

  static ReplicatedData compressed(int timeDelta) {
    return new ReplicatedData(ABSENT, ABSENT, false, timeDelta);
  }
}
