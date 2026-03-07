package androidx.media3.extractor.asf;

import androidx.media3.common.util.ParsableByteArray;

/**
 * Little-endian typed read helpers for {@link ParsableByteArray} that are not already
 * provided by {@link ParsableByteArray} itself.
 */
final class AsfLittleEndian {

  /**
   * Reads an unsigned 64-bit LE integer; values overflowing {@code long} clamp to {@link Long#MAX_VALUE}.
   */
  static long readU64(ParsableByteArray buf) {
    long value = buf.readLittleEndianLong();
    return value < 0 ? Long.MAX_VALUE : value;
  }

  /**
   * Reads a variable-length integer using the ASF PPI length-type encoding:
   * 0 = absent (returns 0), 1 = u8, 2 = u16 LE, 3 = u32 LE.
   */
  static long readVarLen(ParsableByteArray buf, int lenType) {
    switch (lenType) {
      case 1:
        return buf.readUnsignedByte();
      case 2:
        return buf.readLittleEndianUnsignedShort();
      case 3:
        return buf.readLittleEndianUnsignedInt();
      default:
        return 0L;
    }
  }

  /**
   * Reads exactly {@code length} bytes into a new array.
   */
  static byte[] readBytes(ParsableByteArray buf, int length) {
    byte[] bytes = new byte[length];
    buf.readBytes(bytes, 0, length);
    return bytes;
  }
}
