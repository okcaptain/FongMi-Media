package androidx.media3.extractor.asf;

import androidx.media3.common.util.ParsableByteArray;

/**
 * Little-endian typed read helpers for {@link ParsableByteArray}.
 */
final class AsfLittleEndian {

  /**
   * Reads an unsigned 16-bit LE integer.
   */
  static int readU16(ParsableByteArray buf) {
    return buf.readLittleEndianShort() & 0xFFFF;
  }

  /**
   * Reads an unsigned 32-bit LE integer.
   */
  static long readU32(ParsableByteArray buf) {
    return buf.readLittleEndianInt() & 0xFFFFFFFFL;
  }

  /**
   * Reads an unsigned 64-bit LE integer; values overflowing {@code long} clamp to {@link Long#MAX_VALUE}.
   */
  static long readU64(ParsableByteArray buf) {
    long value = buf.readLittleEndianLong();
    return value < 0 ? Long.MAX_VALUE : value;
  }

  /**
   * Reads a signed 32-bit LE integer.
   */
  static int readS32(ParsableByteArray buf) {
    return buf.readLittleEndianInt();
  }

  /**
   * Reads a signed 64-bit LE integer.
   */
  static long readS64(ParsableByteArray buf) {
    return buf.readLittleEndianLong();
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
        return readU16(buf);
      case 3:
        return readU32(buf);
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
