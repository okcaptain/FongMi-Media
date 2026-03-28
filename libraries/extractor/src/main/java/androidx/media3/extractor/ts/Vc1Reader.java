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

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.ts.TsPayloadReader.TrackIdGenerator;
import java.util.Collections;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

@UnstableApi
public final class Vc1Reader implements ElementaryStreamReader {

  private static final int SC_SEQUENCE_HEADER = 0x0F;
  private static final int SC_ENTRY_POINT = 0x0E;
  private static final int SC_FRAME = 0x0D;

  private static final int[] VC1_FPS_NR = {24, 25, 30, 50, 60, 48, 72};
  private static final int[] VC1_FPS_DR = {1000, 1001};
  private static final int[][] VC1_PIXEL_ASPECT = {
      {0, 1}, {1, 1}, {12, 11}, {10, 11}, {16, 11}, {40, 33},
      {24, 11}, {20, 11}, {32, 11}, {80, 33}, {18, 11}, {15, 11}, {64, 33}, {160, 99},
      {0, 1}, {0, 1}
  };

  private final byte[] seqBuf = new byte[24];
  private final byte[] initDataBuf = new byte[512];
  private final String containerMimeType;

  private @MonotonicNonNull TrackOutput output;
  @Nullable
  private ColorInfo parsedColorInfo;
  @Nullable
  private String formatId;
  private boolean formatSet;
  private boolean hasSample;
  private boolean inSeqHeader;
  private boolean hasEntryPoint;
  private int sampleBytesWritten;
  private int sc0, sc1, sc2;
  private int seqBufLen;
  private long timeUs;

  private boolean capturingInitData;
  private boolean initDataHasEntryPoint;
  private int initDataLen;
  private int parsedWidth;
  private int parsedHeight;
  private int parsedFrameRateNum;
  private int parsedFrameRateDen;
  private float parsedPixelWidthHeightRatio;

  public Vc1Reader(String containerMimeType) {
    this.containerMimeType = containerMimeType;
    timeUs = C.TIME_UNSET;
    sc0 = sc1 = sc2 = 0xFF;
  }

  private static int readBits(byte[] buf, int bitOffset, int numBits) {
    int result = 0;
    for (int i = 0; i < numBits; i++) {
      int byteIdx = (bitOffset + i) >> 3;
      int shift = 7 - ((bitOffset + i) & 7);
      if (byteIdx < buf.length) {
        result = (result << 1) | ((buf[byteIdx] >> shift) & 1);
      }
    }
    return result;
  }

  @C.ColorSpace
  private static int mapMatrixCoef(int matrixCoef) {
    switch (matrixCoef) {
      case 1:
        return C.COLOR_SPACE_BT709;
      case 6:
      case 7:
        return C.COLOR_SPACE_BT601;
      default:
        return Format.NO_VALUE;
    }
  }

  @C.ColorTransfer
  private static int mapTransferChar(int transferChar) {
    switch (transferChar) {
      case 1:
      case 7:
        return C.COLOR_TRANSFER_SDR;
      default:
        return Format.NO_VALUE;
    }
  }

  @Override
  public void seek() {
    seqBufLen = 0;
    hasSample = false;
    inSeqHeader = false;
    hasEntryPoint = false;
    timeUs = C.TIME_UNSET;
    sampleBytesWritten = 0;
    sc0 = sc1 = sc2 = 0xFF;
    capturingInitData = false;
    initDataHasEntryPoint = false;
    initDataLen = 0;
    parsedWidth = 0;
    parsedHeight = 0;
    parsedColorInfo = null;
    parsedFrameRateNum = 0;
    parsedFrameRateDen = 0;
    parsedPixelWidthHeightRatio = 0;
  }

  @Override
  public void createTracks(ExtractorOutput extractorOutput, TrackIdGenerator idGenerator) {
    idGenerator.generateNewId();
    formatId = idGenerator.getFormatId();
    output = extractorOutput.track(idGenerator.getTrackId(), C.TRACK_TYPE_VIDEO);
  }

  @Override
  public void packetStarted(long pesTimeUs, @TsPayloadReader.Flags int flags) {
    timeUs = pesTimeUs;
  }

  @Override
  public void consume(ParsableByteArray data) {
    if (output == null || timeUs == C.TIME_UNSET) {
      return;
    }
    int startPos = data.getPosition();
    byte[] buf = data.getData();
    int limit = data.limit();
    for (int i = data.getPosition(); i < limit; i++) {
      int b = buf[i] & 0xFF;
      boolean wasCapturing = capturingInitData;
      if (inSeqHeader) {
        if (seqBufLen < seqBuf.length) {
          seqBuf[seqBufLen++] = (byte) b;
          if (seqBufLen == seqBuf.length) {
            parseSequenceHeader();
            inSeqHeader = false;
          }
        } else {
          inSeqHeader = false;
        }
      }
      if (sc0 == 0x00 && sc1 == 0x00 && sc2 == 0x01) {
        if (b == SC_SEQUENCE_HEADER) {
          seqBufLen = 0;
          inSeqHeader = true;
          if (!formatSet) {
            capturingInitData = true;
            initDataLen = 0;
            initDataHasEntryPoint = false;
            parsedWidth = 0;
            parsedHeight = 0;
            wasCapturing = false;
            if (initDataLen + 4 <= initDataBuf.length) {
              initDataBuf[initDataLen++] = 0x00;
              initDataBuf[initDataLen++] = 0x00;
              initDataBuf[initDataLen++] = 0x01;
              initDataBuf[initDataLen++] = (byte) SC_SEQUENCE_HEADER;
            }
          }
        } else if (b == SC_ENTRY_POINT) {
          hasSample = true;
          hasEntryPoint = true;
          if (capturingInitData) {
            initDataHasEntryPoint = true;
          }
        } else if (b == SC_FRAME) {
          hasSample = true;
          if (capturingInitData && !formatSet) {
            initDataLen = Math.max(0, initDataLen - 3);
            capturingInitData = false;
            wasCapturing = false;
            if (initDataHasEntryPoint && parsedWidth > 0 && parsedHeight > 0 && initDataLen >= 16) {
              buildFormatFromInitData();
            }
          }
        }
      }
      if (wasCapturing && initDataLen < initDataBuf.length) {
        initDataBuf[initDataLen++] = (byte) b;
      }
      sc0 = sc1;
      sc1 = sc2;
      sc2 = b;
    }
    int byteCount = limit - startPos;
    data.setPosition(startPos);
    output.sampleData(data, byteCount);
    sampleBytesWritten += byteCount;
  }

  @Override
  public void packetFinished(boolean isEndOfInput) {
    if (output == null || !hasSample || sampleBytesWritten == 0) {
      return;
    }
    if (formatSet) {
      int flags = hasEntryPoint ? C.BUFFER_FLAG_KEY_FRAME : 0;
      output.sampleMetadata(timeUs, flags, sampleBytesWritten, 0, null);
    }
    sampleBytesWritten = 0;
    hasSample = false;
    hasEntryPoint = false;
  }

  private void parseSequenceHeader() {
    if (seqBufLen < seqBuf.length) {
      return;
    }
    int p = 0;
    int profile = readBits(seqBuf, p, 2);
    p += 2;
    if (profile != 3) {
      return;
    }
    p += 14;
    int codedWidth = readBits(seqBuf, p, 12);
    p += 12;
    int codedHeight = readBits(seqBuf, p, 12);
    p += 12;
    int width = (codedWidth + 1) << 1;
    int height = (codedHeight + 1) << 1;
    if (width <= 0 || width > 4096 || height <= 0 || height > 4096) {
      return;
    }
    parsedWidth = width;
    parsedHeight = height;
    p += 6;
    if (p >= seqBuf.length * 8) {
      return;
    }
    if (readBits(seqBuf, p, 1) == 0) {
      return;
    }
    p += 1;
    if (p + 28 > seqBuf.length * 8) {
      return;
    }
    int dispHoriz = readBits(seqBuf, p, 14) + 1;
    p += 14;
    int dispVert = readBits(seqBuf, p, 14) + 1;
    p += 14;
    if (p >= seqBuf.length * 8) {
      return;
    }
    boolean hasAspectRatio = readBits(seqBuf, p, 1) == 1;
    p += 1;
    if (hasAspectRatio) {
      if (p + 4 > seqBuf.length * 8) {
        return;
      }
      int ar = readBits(seqBuf, p, 4);
      p += 4;
      if (ar > 0 && ar < 14) {
        int sarNum = VC1_PIXEL_ASPECT[ar][0];
        int sarDen = VC1_PIXEL_ASPECT[ar][1];
        if (sarNum > 0 && sarDen > 0) {
          parsedPixelWidthHeightRatio = (float) sarNum / sarDen;
        }
      } else if (ar == 15) {
        if (p + 16 > seqBuf.length * 8) {
          return;
        }
        int sarHoriz = readBits(seqBuf, p, 8) + 1;
        p += 8;
        int sarVert = readBits(seqBuf, p, 8) + 1;
        p += 8;
        parsedPixelWidthHeightRatio = (float) sarHoriz / sarVert;
      } else {
        parsedPixelWidthHeightRatio = ((float) dispHoriz * height) / ((float) dispVert * width);
      }
    } else {
      parsedPixelWidthHeightRatio = ((float) dispHoriz * height) / ((float) dispVert * width);
    }
    if (p >= seqBuf.length * 8) {
      return;
    }
    if (readBits(seqBuf, p, 1) == 1) {
      p += 1;
      if (p >= seqBuf.length * 8) {
        return;
      }
      if (readBits(seqBuf, p, 1) == 1) {
        p += 1;
        if (p + 16 > seqBuf.length * 8) {
          return;
        }
        int framerateNum = readBits(seqBuf, p, 16) + 1;
        p += 16;
        parsedFrameRateNum = framerateNum;
        parsedFrameRateDen = 32;
      } else {
        p += 1;
        if (p + 12 > seqBuf.length * 8) {
          return;
        }
        int nr = readBits(seqBuf, p, 8);
        p += 8;
        int dr = readBits(seqBuf, p, 4);
        p += 4;
        if (nr > 0 && nr <= VC1_FPS_NR.length && dr > 0 && dr <= VC1_FPS_DR.length) {
          parsedFrameRateNum = VC1_FPS_NR[nr - 1] * 1000;
          parsedFrameRateDen = VC1_FPS_DR[dr - 1];
        }
      }
    } else {
      p += 1;
    }
    if (p >= seqBuf.length * 8) {
      return;
    }
    if (readBits(seqBuf, p, 1) == 1) {
      p += 1;
      if (p + 24 > seqBuf.length * 8) {
        return;
      }
      p += 8;
      int transferChar = readBits(seqBuf, p, 8);
      p += 8;
      int matrixCoef = readBits(seqBuf, p, 8);
      @C.ColorSpace int colorSpace = mapMatrixCoef(matrixCoef);
      @C.ColorTransfer int colorTransfer = mapTransferChar(transferChar);
      if (colorSpace != Format.NO_VALUE || colorTransfer != Format.NO_VALUE) {
        parsedColorInfo = new ColorInfo.Builder()
            .setColorSpace(colorSpace)
            .setColorRange(C.COLOR_RANGE_LIMITED)
            .setColorTransfer(colorTransfer)
            .build();
      }
    }
  }

  private void buildFormatFromInitData() {
    if (output == null) {
      return;
    }
    byte[] initData = new byte[initDataLen];
    System.arraycopy(initDataBuf, 0, initData, 0, initDataLen);
    Format.Builder builder = new Format.Builder()
        .setId(formatId)
        .setContainerMimeType(containerMimeType)
        .setSampleMimeType(MimeTypes.VIDEO_VC1)
        .setWidth(parsedWidth)
        .setHeight(parsedHeight)
        .setInitializationData(Collections.singletonList(initData));
    if (parsedFrameRateNum > 0 && parsedFrameRateDen > 0) {
      builder.setFrameRate((float) parsedFrameRateNum / parsedFrameRateDen);
    }
    if (parsedPixelWidthHeightRatio > 0) {
      builder.setPixelWidthHeightRatio(parsedPixelWidthHeightRatio);
    }
    if (parsedColorInfo != null) {
      builder.setColorInfo(parsedColorInfo);
    }
    formatSet = true;
    output.format(builder.build());
  }
}
