package androidx.media3.extractor.asf;

/**
 * One payload extension entry from an Extended Stream Properties Object.
 *
 * <p>Type tag is the first byte of the extension GUID (FFmpeg: {@code ASFPayload.type = g[0]}).
 * Known types: {@code 0x54} = SAR, {@code 0x2A} = DVR-MS timestamps.
 */
final class PayloadExtension {

  static final byte TYPE_SAR = 0x54;
  static final byte TYPE_PTS = 0x2A;
  static final int VARIABLE_SIZE = 0xFFFF; // fixedSize sentinel: size is per-payload LE16

  final byte type;
  final int fixedSize; // bytes of extension data per payload, or VARIABLE_SIZE

  PayloadExtension(byte type, int fixedSize) {
    this.type = type;
    this.fixedSize = fixedSize;
  }
}
