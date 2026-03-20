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
package androidx.media3.extractor.dsd;

import androidx.annotation.NonNull;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.ExtractorInput;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.PositionHolder;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.iso.IsoConstants;
import java.io.EOFException;
import java.io.IOException;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

public final class DsdExtractor implements Extractor {

  private static final int DSD64_SAMPLE_RATE = 2_822_400;
  private static final int DSD64_BYTE_RATE_PER_CHANNEL = DSD64_SAMPLE_RATE / 8;
  private static final int TARGET_SAMPLES_PER_SECOND = 10;
  private static final int SACD_SECTOR_FRAME_RATE = 75;

  private static final int DATA_TYPE_AUDIO = 2;
  private static final int FRAME_INFO_SIZE_DSD = 3;
  private static final int FRAME_INFO_SIZE_DST = 4;
  private static final int MAX_PACKET_COUNT = 7;

  private final int channelCount;
  private final int bytesPerSecond;
  private final int targetSampleSizeBytes;
  private final long lengthLsn;
  private final long durationUs;
  private final String mimeType;
  private final boolean isDst;

  private final byte[] sectorBuffer = new byte[IsoConstants.SECTOR_SIZE];
  private final ParsableByteArray sectorData = new ParsableByteArray(sectorBuffer, IsoConstants.SECTOR_SIZE);
  private final boolean[] pktFrameStarts = new boolean[MAX_PACKET_COUNT];
  private final int[] pktDataTypes = new int[MAX_PACKET_COUNT];
  private final int[] pktLengths = new int[MAX_PACKET_COUNT];

  private @MonotonicNonNull TrackOutput trackOutput;
  private long startTimeUs;
  private long dstFrameCount;
  private long totalBytesOutput;
  private int pendingOutputBytes;
  private int dstPendingFrameBytes;
  private boolean dstSynced;

  public DsdExtractor(int channelCount, long durationUs, String mimeType, long lengthLsn) {
    this.channelCount = channelCount;
    this.durationUs = durationUs;
    this.mimeType = mimeType;
    this.lengthLsn = lengthLsn;
    this.isDst = MimeTypes.AUDIO_DST.equals(mimeType);
    this.bytesPerSecond = channelCount * DSD64_BYTE_RATE_PER_CHANNEL;
    this.targetSampleSizeBytes = bytesPerSecond / TARGET_SAMPLES_PER_SECOND;
  }

  @Override
  public boolean sniff(@NonNull ExtractorInput input) {
    return false;
  }

  @Override
  public void init(ExtractorOutput output) {
    trackOutput = output.track(0, C.TRACK_TYPE_AUDIO);
    output.endTracks();
    trackOutput.format(new Format.Builder()
        .setSampleMimeType(mimeType)
        .setChannelCount(channelCount)
        .setSampleRate(DSD64_BYTE_RATE_PER_CHANNEL)
        .setAverageBitrate(bytesPerSecond * 8)
        .setMaxInputSize(targetSampleSizeBytes)
        .build());
    trackOutput.durationUs(durationUs);
    output.seekMap(new SacdSectorSeekMap(lengthLsn, durationUs));
  }

  @Override
  public void seek(long position, long timeUs) {
    startTimeUs = timeUs;
    totalBytesOutput = 0;
    pendingOutputBytes = 0;
    dstFrameCount = 0;
    dstPendingFrameBytes = 0;
    dstSynced = false;
  }

  @Override
  public void release() {
  }

  @Override
  public @ReadResult int read(@NonNull ExtractorInput input, @NonNull PositionHolder seekPosition) throws IOException {
    if (trackOutput == null) {
      return RESULT_END_OF_INPUT;
    }
    try {
      input.readFully(sectorBuffer, 0, IsoConstants.SECTOR_SIZE);
    } catch (EOFException e) {
      flushPendingSample();
      return RESULT_END_OF_INPUT;
    }
    parseSector();
    if (!isDst && pendingOutputBytes >= targetSampleSizeBytes) {
      flushPendingSample();
    }
    return RESULT_CONTINUE;
  }

  private void parseSector() {
    int offset = 0;
    int headerByte = sectorBuffer[offset++] & 0xFF;
    boolean isDstSector = (headerByte & 1) != 0;
    int frameInfoCount = (headerByte >> 2) & 7;
    int packetInfoCount = (headerByte >> 5) & 7;
    for (int i = 0; i < packetInfoCount; i++) {
      int b0 = sectorBuffer[offset] & 0xFF;
      int b1 = sectorBuffer[offset + 1] & 0xFF;
      pktFrameStarts[i] = (b0 & 0x80) != 0;
      pktDataTypes[i] = (b0 >> 3) & 7;
      pktLengths[i] = ((b0 & 7) << 8) | b1;
      offset += 2;
    }
    offset += frameInfoCount * (isDstSector ? FRAME_INFO_SIZE_DST : FRAME_INFO_SIZE_DSD);
    for (int i = 0; i < packetInfoCount; i++) {
      int len = pktLengths[i];
      if (pktDataTypes[i] == DATA_TYPE_AUDIO && len > 0 && offset + len <= IsoConstants.SECTOR_SIZE) {
        if (isDst) {
          if (pktFrameStarts[i]) {
            if (dstPendingFrameBytes > 0) {
              flushDstFrame();
            }
            dstSynced = true;
          }
          if (dstSynced) {
            sectorData.setPosition(offset);
            trackOutput.sampleData(sectorData, len);
            dstPendingFrameBytes += len;
          }
        } else {
          sectorData.setPosition(offset);
          trackOutput.sampleData(sectorData, len);
          pendingOutputBytes += len;
        }
      }
      offset += len;
    }
  }

  private void flushDstFrame() {
    if (dstPendingFrameBytes <= 0) {
      return;
    }
    long sampleTimeUs = startTimeUs + dstFrameCount * C.MICROS_PER_SECOND / SACD_SECTOR_FRAME_RATE;
    trackOutput.sampleMetadata(sampleTimeUs, C.BUFFER_FLAG_KEY_FRAME, dstPendingFrameBytes, 0, null);
    dstPendingFrameBytes = 0;
    dstFrameCount++;
  }

  private void flushPendingSample() {
    if (isDst) {
      flushDstFrame();
    } else if (pendingOutputBytes > 0) {
      trackOutput.sampleMetadata(sampleTimeUs(), C.BUFFER_FLAG_KEY_FRAME, pendingOutputBytes, 0, null);
      totalBytesOutput += pendingOutputBytes;
      pendingOutputBytes = 0;
    }
  }

  private long sampleTimeUs() {
    return startTimeUs + totalBytesOutput * C.MICROS_PER_SECOND / bytesPerSecond;
  }
}
