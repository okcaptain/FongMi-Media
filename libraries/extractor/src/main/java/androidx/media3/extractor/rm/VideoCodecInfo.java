package androidx.media3.extractor.rm;

/**
 * Parsed RealVideo codec info extracted from MDPR extra data.
 */
final class VideoCodecInfo {

  final String mimeType;
  final String codecs;
  final int width;
  final int height;
  final float fps;
  final byte[] initData;

  VideoCodecInfo(String mimeType, String codecs, int width, int height, float fps, byte[] initData) {
    this.mimeType = mimeType;
    this.codecs = codecs;
    this.width = width;
    this.height = height;
    this.fps = fps;
    this.initData = initData;
  }
}
