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
package androidx.media3.extractor.dts;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.ConstantBitrateSeekMap;
import androidx.media3.extractor.DtsUtil;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.TrackOutput;
import java.io.EOFException;
import java.io.IOException;
import org.checkerframework.checker.nullness.qual.EnsuresNonNull;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.RequiresNonNull;

@UnstableApi
public final class DtsExtractor implements Extractor {

  private static final int MIN_FRAME_SIZE = 96;
  private static final int MIN_VALID_FRAMES = 4;
  private static final int SYNC_SEARCH_STEP = 2;
  private static final int FRAME_HEADER_SIZE = 18;
  private static final int MAX_FRAME_SIZE = 16384;
  private static final int MAX_SNIFF_BYTES = 16 * 1024;
  private static final int EXTSS_HEADER_PREFIX_SIZE = 7;
  private static final int MAX_EXTSS_FRAME_SIZE = 256 * 1024;

  private final ParsableByteArray headerScratch = new ParsableByteArray(FRAME_HEADER_SIZE);

  private @MonotonicNonNull ExtractorOutput extractorOutput;
  private @MonotonicNonNull TrackOutput trackOutput;
  private @MonotonicNonNull Format outputFormat;
  private boolean hasOutputSeekMap;
  private boolean initialized;
  private boolean hasExtss;

  private long startTimeUs;
  private long frameDurationUs;
  private long totalFramesOutput;

  private static void applyExtssOverrides(Format.Builder builder, DtsUtil.DtsHeader extssInfo) {
    builder.setSampleMimeType(extssInfo.mimeType);
    if (extssInfo.channelCount != C.LENGTH_UNSET) {
      builder.setChannelCount(extssInfo.channelCount);
    }
    if (extssInfo.sampleRate != C.RATE_UNSET_INT) {
      builder.setSampleRate(extssInfo.sampleRate);
    }
    if (extssInfo.bitrate > 0) {
      builder.setAverageBitrate(extssInfo.bitrate);
    }
  }

  @Override
  public boolean sniff(@NonNull ExtractorInput input) throws IOException {
    return !DtsUtil.isRiffContainer(input) && findValidFrameSequence(input);
  }

  @Override
  @EnsuresNonNull({"extractorOutput", "trackOutput"})
  public void init(ExtractorOutput output) {
    extractorOutput = output;
    trackOutput = output.track(0, C.TRACK_TYPE_AUDIO);
    output.endTracks();
  }

  @Override
  public void seek(long position, long timeUs) {
    startTimeUs = timeUs;
    totalFramesOutput = 0;
    initialized = false;
    hasExtss = false;
  }

  @Override
  public void release() {
  }

  @Override
  @RequiresNonNull({"extractorOutput", "trackOutput"})
  public @ReadResult int read(@NonNull ExtractorInput input, @NonNull PositionHolder seekPosition) throws IOException {
    if (!initialized) {
      if (!initFromStream(input)) {
        return RESULT_END_OF_INPUT;
      }
    }
    return readFrame(input);
  }

  private boolean findValidFrameSequence(ExtractorInput input) throws IOException {
    byte[] header = headerScratch.getData();
    int peekOffset = 0;
    int validFrames = 0;
    while (peekOffset < MAX_SNIFF_BYTES) {
      int frameType = peekFrameTypeAt(input, header, peekOffset);
      if (frameType == -1) {
        break;
      }
      if (frameType == DtsUtil.FRAME_TYPE_CORE) {
        int size = DtsUtil.getDtsFrameSize(header);
        if (size >= MIN_FRAME_SIZE && size <= MAX_FRAME_SIZE) {
          if (++validFrames >= MIN_VALID_FRAMES) {
            input.resetPeekPosition();
            return true;
          }
          peekOffset += size;
          continue;
        }
      } else if (frameType == DtsUtil.FRAME_TYPE_EXTENSION_SUBSTREAM) {
        int size = DtsUtil.parseDtsHdFrameSize(header);
        if (size >= MIN_FRAME_SIZE) {
          peekOffset += size;
          continue;
        }
      }
      validFrames = 0;
      peekOffset += SYNC_SEARCH_STEP;
    }
    input.resetPeekPosition();
    return false;
  }

  private int peekFrameTypeAt(ExtractorInput input, byte[] header, int peekOffset) throws IOException {
    input.resetPeekPosition();
    input.advancePeekPosition(peekOffset);
    try {
      input.peekFully(header, 0, FRAME_HEADER_SIZE);
    } catch (EOFException e) {
      return -1;
    }
    return DtsUtil.getFrameType(DtsUtil.readSyncWord(header));
  }

  @RequiresNonNull({"extractorOutput", "trackOutput"})
  private boolean initFromStream(ExtractorInput input) throws IOException {
    byte[] header = headerScratch.getData();
    int scanned = 0;
    while (scanned < MAX_SNIFF_BYTES) {
      if (!peekCurrentHeader(input, header)) {
        return false;
      }
      int frameType = DtsUtil.getFrameType(DtsUtil.readSyncWord(header));
      if (frameType == DtsUtil.FRAME_TYPE_CORE) {
        int size = DtsUtil.getDtsFrameSize(header);
        if (size >= MIN_FRAME_SIZE && size <= MAX_FRAME_SIZE) {
          initializeFromValidCore(input, header, size);
          return true;
        }
      } else if (frameType == DtsUtil.FRAME_TYPE_EXTENSION_SUBSTREAM) {
        int size = DtsUtil.parseDtsHdFrameSize(header);
        if (size >= MIN_FRAME_SIZE) {
          try {
            input.skipFully(size);
          } catch (EOFException e) {
            return false;
          }
          scanned += size;
          continue;
        }
      }
      try {
        input.skipFully(SYNC_SEARCH_STEP);
      } catch (EOFException e) {
        return false;
      }
      scanned += SYNC_SEARCH_STEP;
    }
    return false;
  }

  private boolean peekCurrentHeader(ExtractorInput input, byte[] header) throws IOException {
    input.resetPeekPosition();
    try {
      input.peekFully(header, 0, FRAME_HEADER_SIZE);
      return true;
    } catch (EOFException e) {
      return false;
    }
  }

  @RequiresNonNull({"extractorOutput", "trackOutput"})
  private void initializeFromValidCore(ExtractorInput input, byte[] header, int coreFrameSize) {
    DtsUtil.DtsHeader extssInfo = peekExtssAfterCore(input, coreFrameSize);
    if (extssInfo != null) {
      hasExtss = true;
    }
    setupFromCoreHeader(header, coreFrameSize, extssInfo, input);
    initialized = true;
  }

  @Nullable
  private DtsUtil.DtsHeader peekExtssAfterCore(ExtractorInput input, int coreFrameSize) {
    try {
      input.advancePeekPosition(coreFrameSize - FRAME_HEADER_SIZE);
      return readPeekedExtssHeader(input);
    } catch (IOException e) {
      return null;
    }
  }

  @Nullable
  private DtsUtil.DtsHeader peekExtssAtReadPosition(ExtractorInput input) {
    try {
      input.resetPeekPosition();
      return readPeekedExtssHeader(input);
    } catch (IOException e) {
      return null;
    }
  }

  @Nullable
  private DtsUtil.DtsHeader readPeekedExtssHeader(ExtractorInput input) throws IOException {
    byte[] prefix = new byte[EXTSS_HEADER_PREFIX_SIZE];
    input.peekFully(prefix, 0, EXTSS_HEADER_PREFIX_SIZE);
    if (DtsUtil.getFrameType(DtsUtil.readSyncWord(prefix)) != DtsUtil.FRAME_TYPE_EXTENSION_SUBSTREAM) {
      return null;
    }
    int headerSize = DtsUtil.parseDtsHdHeaderSize(prefix);
    if (headerSize < EXTSS_HEADER_PREFIX_SIZE || headerSize > MAX_EXTSS_FRAME_SIZE) {
      return null;
    }
    byte[] extssHeader = new byte[headerSize];
    System.arraycopy(prefix, 0, extssHeader, 0, EXTSS_HEADER_PREFIX_SIZE);
    if (headerSize > EXTSS_HEADER_PREFIX_SIZE) {
      input.peekFully(extssHeader, EXTSS_HEADER_PREFIX_SIZE, headerSize - EXTSS_HEADER_PREFIX_SIZE);
    }
    return DtsUtil.parseDtsHdHeader(extssHeader);
  }

  @RequiresNonNull({"extractorOutput", "trackOutput"})
  private void setupFromCoreHeader(byte[] header, int coreFrameSize, @Nullable DtsUtil.DtsHeader extssInfo, ExtractorInput input) {
    Format coreFormat = DtsUtil.parseDtsFormat(header, null, null, 0, MimeTypes.AUDIO_DTS, null);
    updateFrameDuration(header, coreFormat.sampleRate);
    Format.Builder builder = coreFormat.buildUpon();
    if (extssInfo != null) {
      applyExtssOverrides(builder, extssInfo);
    }
    outputFormat = builder.build();
    trackOutput.format(outputFormat);
    int extssFrameSize = extssInfo != null ? extssInfo.frameSize : 0;
    emitSeekMapIfNeeded(input, coreFrameSize + extssFrameSize);
  }

  private void updateFrameDuration(byte[] header, int sampleRate) {
    int samplesPerFrame = DtsUtil.parseDtsAudioSampleCount(header);
    if (sampleRate > 0 && samplesPerFrame > 0) {
      frameDurationUs = (long) samplesPerFrame * C.MICROS_PER_SECOND / sampleRate;
    }
  }

  @RequiresNonNull("extractorOutput")
  private void emitSeekMapIfNeeded(ExtractorInput input, int frameSize) {
    if (hasOutputSeekMap) {
      return;
    }
    if (frameDurationUs > 0) {
      long bitrate = (long) frameSize * 8L * C.MICROS_PER_SECOND / frameDurationUs;
      extractorOutput.seekMap(new ConstantBitrateSeekMap(input.getLength(), input.getPosition(), (int) bitrate, frameSize));
    } else {
      extractorOutput.seekMap(new SeekMap.Unseekable(C.TIME_UNSET));
    }
    hasOutputSeekMap = true;
  }

  @RequiresNonNull("trackOutput")
  private int tryAppendExtssFrame(ExtractorInput input) throws IOException {
    int extssSize = peekNextExtssSize(input);
    if (extssSize == 0) {
      return 0;
    }
    return readAndDeliverExtssData(input, extssSize) ? extssSize : 0;
  }

  private int peekNextExtssSize(ExtractorInput input) throws IOException {
    byte[] header = headerScratch.getData();
    input.resetPeekPosition();
    try {
      input.peekFully(header, 0, FRAME_HEADER_SIZE);
    } catch (EOFException e) {
      return 0;
    }
    if (DtsUtil.getFrameType(DtsUtil.readSyncWord(header)) != DtsUtil.FRAME_TYPE_EXTENSION_SUBSTREAM) {
      return 0;
    }
    int extssSize = DtsUtil.parseDtsHdFrameSize(header);
    return (extssSize >= MIN_FRAME_SIZE && extssSize <= MAX_EXTSS_FRAME_SIZE) ? extssSize : 0;
  }

  @RequiresNonNull("trackOutput")
  private boolean readAndDeliverExtssData(ExtractorInput input, int extssSize) throws IOException {
    byte[] header = headerScratch.getData();
    try {
      input.readFully(header, 0, FRAME_HEADER_SIZE);
    } catch (EOFException e) {
      return false;
    }
    headerScratch.setPosition(0);
    trackOutput.sampleData(headerScratch, FRAME_HEADER_SIZE);
    int remaining = extssSize - FRAME_HEADER_SIZE;
    try {
      while (remaining > 0) {
        remaining -= trackOutput.sampleData(input, remaining, /* allowEndOfInput= */ false);
      }
    } catch (EOFException e) {
      return false;
    }
    return true;
  }

  @RequiresNonNull("trackOutput")
  private @ReadResult int readFrame(ExtractorInput input) throws IOException {
    try {
      input.readFully(headerScratch.getData(), 0, FRAME_HEADER_SIZE);
    } catch (EOFException e) {
      return RESULT_END_OF_INPUT;
    }
    int frameType = DtsUtil.getFrameType(DtsUtil.readSyncWord(headerScratch.getData()));
    if (frameType == DtsUtil.FRAME_TYPE_EXTENSION_SUBSTREAM) {
      int extssSize = DtsUtil.parseDtsHdFrameSize(headerScratch.getData());
      if (extssSize > FRAME_HEADER_SIZE) {
        try {
          input.skipFully(extssSize - FRAME_HEADER_SIZE);
        } catch (EOFException e) {
          return RESULT_END_OF_INPUT;
        }
      } else {
        initialized = false;
      }
      return RESULT_CONTINUE;
    }
    if (frameType == DtsUtil.FRAME_TYPE_UHD_SYNC || frameType == DtsUtil.FRAME_TYPE_UHD_NON_SYNC) {
      try {
        input.skipFully(SYNC_SEARCH_STEP);
      } catch (EOFException e) {
        return RESULT_END_OF_INPUT;
      }
      initialized = false;
      return RESULT_CONTINUE;
    }
    if (frameType != DtsUtil.FRAME_TYPE_CORE) {
      initialized = false;
      return RESULT_CONTINUE;
    }
    int size = DtsUtil.getDtsFrameSize(headerScratch.getData());
    if (size < MIN_FRAME_SIZE || size > MAX_FRAME_SIZE) {
      initialized = false;
      return RESULT_CONTINUE;
    }
    return outputCoreSample(input, size);
  }

  @RequiresNonNull("trackOutput")
  private @ReadResult int outputCoreSample(ExtractorInput input, int coreFrameSize) throws IOException {
    if (!deliverCoreFrameData(input, coreFrameSize)) {
      return RESULT_END_OF_INPUT;
    }
    int extssSize = appendExtssIfPresent(input);
    emitSampleMetadata(coreFrameSize + extssSize);
    return RESULT_CONTINUE;
  }

  @RequiresNonNull("trackOutput")
  private boolean deliverCoreFrameData(ExtractorInput input, int coreFrameSize) throws IOException {
    headerScratch.setPosition(0);
    trackOutput.sampleData(headerScratch, FRAME_HEADER_SIZE);
    int remaining = coreFrameSize - FRAME_HEADER_SIZE;
    try {
      while (remaining > 0) {
        remaining -= trackOutput.sampleData(input, remaining, false);
      }
    } catch (EOFException e) {
      return false;
    }
    return true;
  }

  @RequiresNonNull("trackOutput")
  private int appendExtssIfPresent(ExtractorInput input) throws IOException {
    DtsUtil.DtsHeader extssInfo = hasExtss ? null : peekExtssAtReadPosition(input);
    int extssSize = tryAppendExtssFrame(input);
    if (extssSize > 0 && !hasExtss) {
      hasExtss = true;
      upgradeFormatWithExtss(extssInfo);
    }
    return extssSize;
  }

  @RequiresNonNull("trackOutput")
  private void upgradeFormatWithExtss(@Nullable DtsUtil.DtsHeader extssInfo) {
    if (outputFormat == null) {
      return;
    }
    Format.Builder builder = outputFormat.buildUpon();
    if (extssInfo != null) {
      applyExtssOverrides(builder, extssInfo);
    } else {
      builder.setSampleMimeType(MimeTypes.AUDIO_DTS_HD);
    }
    outputFormat = builder.build();
    trackOutput.format(outputFormat);
  }

  @RequiresNonNull("trackOutput")
  private void emitSampleMetadata(int totalSize) {
    long sampleTimeUs = frameDurationUs > 0 ? startTimeUs + totalFramesOutput * frameDurationUs : 0;
    totalFramesOutput++;
    trackOutput.sampleMetadata(sampleTimeUs, C.BUFFER_FLAG_KEY_FRAME, totalSize, 0, null);
  }
}
