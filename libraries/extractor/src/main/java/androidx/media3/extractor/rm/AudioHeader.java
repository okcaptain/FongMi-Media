package androidx.media3.extractor.rm;

/**
 * Parsed RealAudio header fields from MDPR extra data (v4/v5 only).
 */
final class AudioHeader {

  final int flavor;
  final int subPacketH;
  final int frameSize;
  final int subPacketSize;
  final int sampleRate;
  final int channels;
  final String codecFourCC;
  final byte[] codecExtraData;

  AudioHeader(int flavor, int subPacketH, int frameSize, int subPacketSize, int sampleRate, int channels, String codecFourCC, byte[] codecExtraData) {
    this.flavor = flavor;
    this.subPacketH = subPacketH;
    this.frameSize = frameSize;
    this.subPacketSize = subPacketSize;
    this.sampleRate = sampleRate;
    this.channels = channels;
    this.codecFourCC = codecFourCC;
    this.codecExtraData = codecExtraData;
  }
}
