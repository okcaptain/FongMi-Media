package androidx.media3.extractor.asf;

import androidx.annotation.Nullable;

/**
 * Immutable descriptor for one ASF audio stream, parsed from a Stream Properties Object.
 */
final class AudioStreamInfo {

  final int streamNumber;
  final int waveFormatTag; // WAVEFORMATEX wFormatTag (e.g. 0x0161 = WMA v2)
  final int channelCount;
  final int sampleRate;
  final int avgBitrateBps; // nAvgBytesPerSec * 8
  final int blockAlign;    // nBlockAlign; used as maxInputSize hint
  @Nullable
  final byte[] codecExtra; // cbData from WAVEFORMATEX

  // Descrambling params from Error Correction Data (FFmpeg: ds_span / ds_packet_size / ds_chunk_size).
  // All zero when absent; dsSpan > 1 means descrambling is required.
  final int dsSpan;
  final int dsPacketSize;
  final int dsChunkSize;

  AudioStreamInfo(int streamNumber, int waveFormatTag, int channelCount, int sampleRate, int avgBytesPerSec, int blockAlign, @Nullable byte[] codecExtra, int dsSpan, int dsPacketSize, int dsChunkSize) {
    this.streamNumber = streamNumber;
    this.waveFormatTag = waveFormatTag;
    this.channelCount = channelCount;
    this.sampleRate = sampleRate;
    this.avgBitrateBps = avgBytesPerSec * 8;
    this.blockAlign = blockAlign;
    this.codecExtra = codecExtra;
    this.dsSpan = dsSpan;
    this.dsPacketSize = dsPacketSize;
    this.dsChunkSize = dsChunkSize;
  }

  boolean requiresDescrambling() {
    return dsSpan > 1;
  }
}
