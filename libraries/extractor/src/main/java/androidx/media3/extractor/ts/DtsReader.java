/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.extractor.ts;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.lang.Math.min;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.extractor.DtsUtil;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.ts.TsPayloadReader.TrackIdGenerator;
import com.google.common.primitives.Ints;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;

/** Parses a continuous DTS or DTS UHD byte stream and extracts individual samples. */
@UnstableApi
public final class DtsReader implements ElementaryStreamReader {

  private static final int STATE_FINDING_SYNC = 0;
  private static final int STATE_READING_CORE_HEADER = 1;
  private static final int STATE_FINDING_EXTSS_HEADER_SIZE = 2;
  private static final int STATE_READING_EXTSS_HEADER = 3;
  private static final int STATE_FINDING_UHD_HEADER_SIZE = 4;
  private static final int STATE_READING_UHD_HEADER = 5;
  private static final int STATE_READING_SAMPLE = 6;
  private static final int STATE_CHECKING_FOR_EXTSS_AFTER_CORE = 7;

  /** Size of core header, in bytes. */
  private static final int CORE_HEADER_SIZE = 18;

  /**
   * Maximum possible size of extension sub-stream header, in bytes. See See ETSI TS 102 114 V1.6.1
   * (2019-08) Section 7.5.2.
   */
  /* package */ static final int EXTSS_HEADER_SIZE_MAX = 4096;

  /**
   * Maximum size of DTS UHD(DTS:X) frame header, in bytes. See ETSI TS 103 491 V1.2.1 (2019-05)
   * Section 6.4.4.3.
   */
  /* package */ static final int FTOC_MAX_HEADER_SIZE = 5408;

  private final byte[] corePostSyncCheck = new byte[4];
  private final ParsableByteArray headerScratchBytes;

  /** The chunk ID is read in synchronized frames and re-used in non-synchronized frames. */
  private final AtomicInteger uhdAudioChunkId;

  @Nullable private final String language;
  private final @C.RoleFlags int roleFlags;
  private final String containerMimeType;

  private @MonotonicNonNull String formatId;
  private @MonotonicNonNull TrackOutput output;

  private int state;
  private int bytesRead;

  /** Used to find the header. */
  private int syncBytes;

  // Used when parsing the header.
  private long sampleDurationUs;
  private @MonotonicNonNull Format format;
  private int sampleSize;
  private @DtsUtil.FrameType int frameType;
  private int extensionSubstreamHeaderSize;
  private int uhdHeaderSize;
  private int coreSampleRate;

  // Used when reading the samples.
  private long timeUs;
  private int xllXScanAccum;
  private int xllXScanCountdown;
  private int pendingCoreSampleSize;
  private int corePostSyncCheckCount;
  private boolean xllXScanDone;
  private boolean xllXScanPending;
  private boolean skipExtssUntilCore;
  private boolean combiningCoreAndExss;
  private boolean coreFormatPendingEmit;

  /**
   * Constructs a new reader for DTS elementary streams.
   *
   * @param language Track language.
   * @param roleFlags Track role flags.
   * @param maxHeaderSize Maximum size of the header in a frame.
   * @param containerMimeType The MIME type of the container holding the stream.
   */
  public DtsReader(
      @Nullable String language,
      @C.RoleFlags int roleFlags,
      int maxHeaderSize,
      String containerMimeType) {
    headerScratchBytes = new ParsableByteArray(new byte[maxHeaderSize]);
    state = STATE_FINDING_SYNC;
    timeUs = C.TIME_UNSET;
    uhdAudioChunkId = new AtomicInteger();
    extensionSubstreamHeaderSize = C.LENGTH_UNSET;
    uhdHeaderSize = C.LENGTH_UNSET;
    this.language = language;
    this.roleFlags = roleFlags;
    this.containerMimeType = containerMimeType;
  }

  @Override
  public void seek() {
    state = STATE_FINDING_SYNC;
    bytesRead = 0;
    syncBytes = 0;
    timeUs = C.TIME_UNSET;
    uhdAudioChunkId.set(0);
    xllXScanAccum = 0;
    xllXScanCountdown = 0;
    pendingCoreSampleSize = 0;
    corePostSyncCheckCount = 0;
    xllXScanPending = false;
    combiningCoreAndExss = false;
    coreFormatPendingEmit = false;
    skipExtssUntilCore = (coreSampleRate != 0);
  }

  @Override
  public void createTracks(ExtractorOutput extractorOutput, TrackIdGenerator idGenerator) {
    idGenerator.generateNewId();
    formatId = idGenerator.getFormatId();
    output = extractorOutput.track(idGenerator.getTrackId(), C.TRACK_TYPE_AUDIO);
  }

  @Override
  public void packetStarted(long pesTimeUs, @TsPayloadReader.Flags int flags) {
    if (pesTimeUs != C.TIME_UNSET) {
      timeUs = pesTimeUs;
    }
  }

  @Override
  public void consume(ParsableByteArray data) throws ParserException {
    // Asserts that createTracks has been called.
    checkNotNull(output);
    while (data.bytesLeft() > 0) {
      switch (state) {
        case STATE_FINDING_SYNC:
          if (skipToNextSyncWord(data)) {
            if (skipExtssUntilCore && frameType == DtsUtil.FRAME_TYPE_EXTENSION_SUBSTREAM) {
              bytesRead = 0;
            } else {
              if (frameType == DtsUtil.FRAME_TYPE_CORE) {
                skipExtssUntilCore = false;
              }
              state = stateForFrameType(frameType);
            }
          }
          break;
        case STATE_READING_CORE_HEADER:
          if (continueRead(data, headerScratchBytes.getData(), CORE_HEADER_SIZE)) {
            parseCoreHeader();
            headerScratchBytes.setPosition(0);
            output.sampleData(headerScratchBytes, CORE_HEADER_SIZE);
            state = STATE_READING_SAMPLE;
          }
          break;
        case STATE_FINDING_EXTSS_HEADER_SIZE:
          // Read enough bytes to parse the header size information.
          if (continueRead(data, headerScratchBytes.getData(), /* targetLength= */ 7)) {
            extensionSubstreamHeaderSize =
                DtsUtil.parseDtsHdHeaderSize(headerScratchBytes.getData());
            state = STATE_READING_EXTSS_HEADER;
          }
          break;
        case STATE_READING_EXTSS_HEADER:
          if (continueRead(data, headerScratchBytes.getData(), extensionSubstreamHeaderSize)) {
            if (combiningCoreAndExss) {
              parseCombinedCoreExssHeader();
            } else {
              parseExtensionSubstreamHeader();
            }
            headerScratchBytes.setPosition(0);
            output.sampleData(headerScratchBytes, extensionSubstreamHeaderSize);
            state = STATE_READING_SAMPLE;
          }
          break;
        case STATE_FINDING_UHD_HEADER_SIZE:
          // Read enough bytes to parse the header size information.
          if (continueRead(data, headerScratchBytes.getData(), /* targetLength= */ 6)) {
            uhdHeaderSize = DtsUtil.parseDtsUhdHeaderSize(headerScratchBytes.getData());
            // Adjust the array read position if data read is more than the actual header size.
            if (bytesRead > uhdHeaderSize) {
              int extraBytes = bytesRead - uhdHeaderSize;
              bytesRead -= extraBytes;
              data.setPosition(data.getPosition() - extraBytes);
            }
            state = STATE_READING_UHD_HEADER;
          }
          break;
        case STATE_READING_UHD_HEADER:
          if (continueRead(data, headerScratchBytes.getData(), uhdHeaderSize)) {
            parseUhdHeader();
            headerScratchBytes.setPosition(0);
            output.sampleData(headerScratchBytes, uhdHeaderSize);
            state = STATE_READING_SAMPLE;
          }
          break;
        case STATE_READING_SAMPLE:
          int bytesToRead = min(data.bytesLeft(), sampleSize - bytesRead);
          if (xllXScanPending) {
            scanForXllX(data.getData(), data.getPosition(), bytesToRead);
          }
          output.sampleData(data, bytesToRead);
          bytesRead += bytesToRead;
          if (bytesRead == sampleSize) {
            onSampleComplete();
          }
          break;
        case STATE_CHECKING_FOR_EXTSS_AFTER_CORE:
          while (corePostSyncCheckCount < 4 && data.bytesLeft() > 0) {
            corePostSyncCheck[corePostSyncCheckCount++] = (byte) data.readUnsignedByte();
          }
          if (corePostSyncCheckCount == 4) {
            int peekedSyncWord = DtsUtil.readSyncWord(corePostSyncCheck);
            if (DtsUtil.getFrameType(peekedSyncWord) == DtsUtil.FRAME_TYPE_EXTENSION_SUBSTREAM) {
              combiningCoreAndExss = true;
              System.arraycopy(corePostSyncCheck, 0, headerScratchBytes.getData(), 0, 4);
              bytesRead = 4;
              frameType = DtsUtil.FRAME_TYPE_EXTENSION_SUBSTREAM;
              state = STATE_FINDING_EXTSS_HEADER_SIZE;
            } else {
              emitPendingCoreOnlySample();
            }
          }
          break;
        default:
          throw new IllegalStateException();
      }
    }
  }

  @Override
  public void packetFinished(boolean isEndOfInput) {
    if (isEndOfInput && pendingCoreSampleSize > 0) {
      emitPendingCoreOnlySample();
    }
  }

  /**
   * Sets the {@link TrackOutput} to receive DTS samples, along with a format ID.
   *
   * <p>This is an alternative to {@link #createTracks(ExtractorOutput, TrackIdGenerator)}, for use
   * when the track output is managed externally (e.g. by {@link
   * androidx.media3.extractor.wav.WavExtractor}).
   *
   * @param trackOutput The track output to receive samples.
   * @param formatId The format identifier.
   */
  public void setTrackOutput(TrackOutput trackOutput, String formatId) {
    this.formatId = formatId;
    this.output = trackOutput;
  }

  /**
   * Continues a read from the provided {@code source} into a given {@code target}. It's assumed
   * that the data should be written into {@code target} starting from an offset of zero.
   *
   * @param source The source from which to read.
   * @param target The target into which data is to be read.
   * @param targetLength The target length of the read.
   * @return Whether the target length was reached.
   */
  private boolean continueRead(ParsableByteArray source, byte[] target, int targetLength) {
    int bytesToRead = min(source.bytesLeft(), targetLength - bytesRead);
    source.readBytes(target, bytesRead, bytesToRead);
    bytesRead += bytesToRead;
    return bytesRead == targetLength;
  }

  /**
   * Locates the next SYNC word value in the buffer, advancing the position to the byte that
   * immediately follows it. If SYNC was not located, the position is advanced to the limit.
   *
   * @param pesBuffer The buffer whose position should be advanced.
   * @return Whether SYNC word was found.
   */
  private boolean skipToNextSyncWord(ParsableByteArray pesBuffer) {
    while (pesBuffer.bytesLeft() > 0) {
      syncBytes <<= 8;
      syncBytes |= pesBuffer.readUnsignedByte();
      frameType = DtsUtil.getFrameType(syncBytes);
      if (frameType != DtsUtil.FRAME_TYPE_UNKNOWN) {
        writeSyncWordToHeader(syncBytes);
        bytesRead = 4;
        syncBytes = 0;
        return true;
      }
    }
    return false;
  }

  /** Emits a completed sample using the current {@link #sampleSize}. */
  @RequiresNonNull("output")
  private void emitSample(@C.BufferFlags int flags) {
    emitSample(flags, sampleSize);
  }

  /** Emits a completed sample with an explicit size, then advances {@link #timeUs}. */
  @RequiresNonNull("output")
  private void emitSample(@C.BufferFlags int flags, int size) {
    checkState(timeUs != C.TIME_UNSET);
    output.sampleMetadata(timeUs, flags, size, 0, null);
    timeUs += sampleDurationUs;
  }

  /** No EXSS follows core: emit the deferred core format if pending, then output the sample. */
  @RequiresNonNull("output")
  private void emitPendingCoreOnlySample() {
    if (coreFormatPendingEmit) {
      output.format(format);
      coreFormatPendingEmit = false;
    }
    emitSample(C.BUFFER_FLAG_KEY_FRAME, pendingCoreSampleSize);
    pendingCoreSampleSize = 0;
    reprocessPeekedBytes();
  }

  /** EXSS follows core: parse EXSS header, merge sizes, and emit HD format instead of core. */
  @RequiresNonNull("output")
  private void parseCombinedCoreExssHeader() throws ParserException {
    DtsUtil.DtsHeader exssHeader = DtsUtil.parseDtsHdHeader(headerScratchBytes.getData());
    coreFormatPendingEmit = false;
    updateFormatWithDtsHeaderInfo(exssHeader);
    if (exssHeader.frameDurationUs != C.TIME_UNSET) {
      sampleDurationUs = exssHeader.frameDurationUs;
    }
    sampleSize = pendingCoreSampleSize + exssHeader.frameSize;
    bytesRead = pendingCoreSampleSize + extensionSubstreamHeaderSize;
    maybeStartXllXScan(exssHeader.mimeType, exssHeader.frameSize - extensionSubstreamHeaderSize);
  }

  /**
   * Re-processes the 4 bytes in {@link #corePostSyncCheck} through sync detection, in case they
   * contain the start of a new frame sync word.
   */
  private void reprocessPeekedBytes() {
    syncBytes = 0;
    bytesRead = 0;
    state = STATE_FINDING_SYNC;
    for (int i = 0; i < 4; i++) {
      syncBytes = (syncBytes << 8) | (corePostSyncCheck[i] & 0xFF);
      frameType = DtsUtil.getFrameType(syncBytes);
      if (frameType != DtsUtil.FRAME_TYPE_UNKNOWN) {
        writeSyncWordToHeader(syncBytes);
        bytesRead = 4;
        syncBytes = 0;
        state = stateForFrameType(frameType);
        break;
      }
    }
  }

  /** Writes the 4-byte sync word into the start of {@link #headerScratchBytes}. */
  private void writeSyncWordToHeader(int syncWord) {
    byte[] hd = headerScratchBytes.getData();
    hd[0] = (byte) (syncWord >>> 24);
    hd[1] = (byte) (syncWord >>> 16);
    hd[2] = (byte) (syncWord >>> 8);
    hd[3] = (byte) syncWord;
  }

  /** Returns the next state to transition to after identifying a sync word's frame type. */
  private static int stateForFrameType(@DtsUtil.FrameType int frameType) {
    if (frameType == DtsUtil.FRAME_TYPE_UHD_SYNC || frameType == DtsUtil.FRAME_TYPE_UHD_NON_SYNC) {
      return STATE_FINDING_UHD_HEADER_SIZE;
    } else if (frameType == DtsUtil.FRAME_TYPE_CORE) {
      return STATE_READING_CORE_HEADER;
    } else {
      return STATE_FINDING_EXTSS_HEADER_SIZE;
    }
  }

  /** Parses the DTS Core Sub-stream header. */
  @RequiresNonNull("output")
  private void parseCoreHeader() {
    byte[] frameData = headerScratchBytes.getData();
    if (format == null) {
      format =
          DtsUtil.parseDtsFormat(frameData, formatId, language, roleFlags, containerMimeType, null);
      coreFormatPendingEmit = true;
    }
    coreSampleRate = format.sampleRate;
    sampleSize = DtsUtil.getDtsFrameSize(frameData);
    // In this class a sample is an access unit (frame in DTS), but the format's sample rate
    // specifies the number of PCM audio samples per second.
    sampleDurationUs =
        Ints.checkedCast(
            Util.sampleCountToDurationUs(
                DtsUtil.parseDtsAudioSampleCount(frameData), format.sampleRate));
  }

  /** Parses the DTS Extension Sub-stream header. */
  @RequiresNonNull("output")
  private void parseExtensionSubstreamHeader() throws ParserException {
    DtsUtil.DtsHeader dtsHeader = DtsUtil.parseDtsHdHeader(headerScratchBytes.getData());
    updateFormatWithDtsHeaderInfo(dtsHeader);
    sampleSize = dtsHeader.frameSize;
    if (dtsHeader.frameDurationUs != C.TIME_UNSET) {
      sampleDurationUs = dtsHeader.frameDurationUs;
    }
    maybeStartXllXScan(dtsHeader.mimeType, sampleSize - extensionSubstreamHeaderSize);
  }

  /**
   * Arms the XLL-X payload scanner if this is the first DTS-HD MA EXTSS frame seen and the scan
   * has not yet completed. The scanner will identify whether the stream carries embedded DTS:X
   * (XLL-X) object audio by looking for the XLL-X sync word during {@link #STATE_READING_SAMPLE}.
   *
   * @param mimeType    The MIME type resolved from the EXTSS header.
   * @param payloadSize The byte length of the XLL payload (frame size minus header size).
   */
  private void maybeStartXllXScan(String mimeType, int payloadSize) {
    if (!xllXScanDone && MimeTypes.AUDIO_DTS_MA.equals(mimeType) && payloadSize > 0) {
      xllXScanPending = true;
      xllXScanAccum = 0;
      xllXScanCountdown = min(payloadSize, DtsUtil.XLL_X_SCAN_MAX_BYTES);
    }
  }

  /**
   * Scans {@code len} bytes starting at {@code raw[pos]} for the XLL-X (DTS:X) sync word, feeding
   * bytes into the rolling accumulator {@link #xllXScanAccum}. On match, upgrades the track format
   * to {@link MimeTypes#AUDIO_DTS_X} and marks the scan complete. If the scan budget is exhausted
   * without a match, also marks the scan complete.
   */
  @RequiresNonNull("output")
  private void scanForXllX(byte[] raw, int pos, int scanLen) {
    int len = min(scanLen, xllXScanCountdown);
    for (int i = 0; i < len; i++) {
      xllXScanAccum = (xllXScanAccum << 8) | (raw[pos + i] & 0xFF);
      if (DtsUtil.matchesXllXSyncWord(xllXScanAccum)) {
        xllXScanDone = true;
        xllXScanPending = false;
        xllXScanCountdown = 0;
        if (format != null && !MimeTypes.AUDIO_DTS_X.equals(format.sampleMimeType)) {
          format = format.buildUpon().setSampleMimeType(MimeTypes.AUDIO_DTS_X).build();
          output.format(format);
        }
        return;
      }
    }
    xllXScanCountdown -= len;
    if (xllXScanCountdown <= 0) {
      xllXScanPending = false;
      xllXScanDone = true;
    }
  }

  /** Handles the state transition out of {@link #STATE_READING_SAMPLE} once a sample is complete. */
  @RequiresNonNull("output")
  private void onSampleComplete() {
    if (frameType == DtsUtil.FRAME_TYPE_CORE) {
      pendingCoreSampleSize = sampleSize;
      combiningCoreAndExss = false;
      corePostSyncCheckCount = 0;
      bytesRead = 0;
      state = STATE_CHECKING_FOR_EXTSS_AFTER_CORE;
    } else {
      int sampleFlags = frameType == DtsUtil.FRAME_TYPE_UHD_NON_SYNC ? 0 : C.BUFFER_FLAG_KEY_FRAME;
      emitSample(sampleFlags);
      pendingCoreSampleSize = 0;
      combiningCoreAndExss = false;
      state = STATE_FINDING_SYNC;
    }
  }

  /** Parses the UHD frame header. */
  @RequiresNonNull({"output"})
  private void parseUhdHeader() throws ParserException {
    DtsUtil.DtsHeader dtsHeader =
        DtsUtil.parseDtsUhdHeader(headerScratchBytes.getData(), uhdAudioChunkId);
    // Format updates will happen only in FTOC sync frames.
    if (frameType == DtsUtil.FRAME_TYPE_UHD_SYNC) {
      updateFormatWithDtsHeaderInfo(dtsHeader);
    }
    sampleSize = dtsHeader.frameSize;
    if (dtsHeader.frameDurationUs != C.TIME_UNSET) {
      sampleDurationUs = dtsHeader.frameDurationUs;
    }
  }

  @RequiresNonNull({"output"})
  private void updateFormatWithDtsHeaderInfo(DtsUtil.DtsHeader dtsHeader) {
    if (dtsHeader.sampleRate == C.RATE_UNSET_INT || dtsHeader.channelCount == C.LENGTH_UNSET) {
      return;
    }
    if (format == null
        || dtsHeader.channelCount != format.channelCount
        || dtsHeader.sampleRate != format.sampleRate
        || !Objects.equals(dtsHeader.mimeType, format.sampleMimeType)) {
      Format.Builder formatBuilder = format == null ? new Format.Builder() : format.buildUpon();
      int resolvedBitrate = dtsHeader.bitrate > 0 ? dtsHeader.bitrate : Format.NO_VALUE;
      format =
          formatBuilder
              .setId(formatId)
              .setContainerMimeType(containerMimeType)
              .setSampleMimeType(dtsHeader.mimeType)
              .setChannelCount(dtsHeader.channelCount)
              .setSampleRate(dtsHeader.sampleRate)
              .setAverageBitrate(resolvedBitrate)
              .setLanguage(language)
              .setRoleFlags(roleFlags)
              .build();
      output.format(format);
    }
  }
}
