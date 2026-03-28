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
package androidx.media3.extractor.iso.bdmv;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.CodecSpecificDataUtil;
import androidx.media3.extractor.ForwardingTrackOutput;
import androidx.media3.extractor.TrackOutput;

final class HdrTrackOutput extends ForwardingTrackOutput {

  private final int dynamicRangeType;
  private final BdmvTsPayloadReaderFactory payloadReaderFactory;

  HdrTrackOutput(TrackOutput trackOutput, int dynamicRangeType, BdmvTsPayloadReaderFactory payloadReaderFactory) {
    super(trackOutput);
    this.dynamicRangeType = dynamicRangeType;
    this.payloadReaderFactory = payloadReaderFactory;
  }

  private static void applyHdrColorFallback(Format.Builder builder, @Nullable ColorInfo colorInfo, int dynamicRangeType) {
    ColorInfo.Builder colorBuilder = colorInfo != null ? colorInfo.buildUpon() : new ColorInfo.Builder();
    boolean fill = colorInfo == null;
    if (fill || colorInfo.colorTransfer == Format.NO_VALUE) {
      boolean isHlg = dynamicRangeType == BdmvConstants.DYNAMIC_RANGE_HLG;
      colorBuilder.setColorTransfer(isHlg ? C.COLOR_TRANSFER_HLG : C.COLOR_TRANSFER_ST2084);
    }
    if (fill || colorInfo.colorSpace == Format.NO_VALUE) {
      colorBuilder.setColorSpace(C.COLOR_SPACE_BT2020);
    }
    if (fill || colorInfo.colorRange == Format.NO_VALUE) {
      colorBuilder.setColorRange(C.COLOR_RANGE_LIMITED);
    }
    builder.setColorInfo(colorBuilder.build());
  }

  private static int dvLevelFromCodecs(@Nullable String codecs) {
    if (codecs == null) {
      return 9;
    }
    try {
      String[] parts = codecs.split("\\.");
      String prefix = parts[0];
      if (prefix.equals("avc1") || prefix.equals("avc3")) {
        if (parts.length >= 2 && parts[1].length() >= 6) {
          int levelIdc = Integer.parseInt(parts[1].substring(4, 6), 16);
          return avcLevelIdcToDvLevel(levelIdc);
        }
      } else {
        int idc = Integer.parseInt(parts[3].substring(1));
        return hevcLevelIdcToDvLevel(idc);
      }
    } catch (ArrayIndexOutOfBoundsException | NumberFormatException | StringIndexOutOfBoundsException ignored) {
    }
    return 9;
  }

  private static int avcLevelIdcToDvLevel(int idc) {
    if (idc <= 31) {
      return 1;
    }
    if (idc == 32) {
      return 2;
    }
    if (idc <= 40) {
      return 3;
    }
    if (idc == 41) {
      return 4;
    }
    if (idc == 42) {
      return 5;
    }
    return 6;
  }

  private static int hevcLevelIdcToDvLevel(int idc) {
    if (idc <= 30) {
      return 1;
    }
    if (idc <= 63) {
      return 2;
    }
    if (idc <= 90) {
      return 3;
    }
    if (idc <= 120) {
      return 4;
    }
    if (idc == 123) {
      return 5;
    }
    if (idc <= 150) {
      return 7;
    }
    if (idc == 153) {
      return 9;
    }
    if (idc == 156) {
      return 10;
    }
    return 13;
  }

  @Override
  public void format(@NonNull Format format) {
    super.format(upgradeFormat(format));
  }

  private Format upgradeFormat(Format format) {
    Format.Builder builder = format.buildUpon();
    int dvProfile = payloadReaderFactory.getDvProfile();
    boolean shouldApplyDv = dvProfile > 0 && dynamicRangeType == BdmvConstants.DYNAMIC_RANGE_DOLBY_VISION;
    if (shouldApplyDv) {
      applyDolbyVision(builder, format, dvProfile);
    }
    applyHdrColorFallback(builder, format.colorInfo, dynamicRangeType);
    return builder.build();
  }

  private void applyDolbyVision(Format.Builder builder, Format format, int dvProfile) {
    int explicitLevel = payloadReaderFactory.getDvLevel();
    int dvLevel = explicitLevel > 0 ? explicitLevel : dvLevelFromCodecs(format.codecs);
    builder.setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION);
    builder.setCodecs(CodecSpecificDataUtil.buildDolbyVisionCodecString(dvProfile, dvLevel));
    byte[] dvCsd = CodecSpecificDataUtil.buildDolbyVisionInitializationData(dvProfile, dvLevel);
    int dvBlCompatId = payloadReaderFactory.getDvBlCompatId();
    if (dvBlCompatId >= 0) {
      dvCsd[4] = (byte) ((dvBlCompatId << 4) | (payloadReaderFactory.getDvMdCompression() << 2));
    }
    builder.setInitializationData(CodecSpecificDataUtil.setDolbyVisionCsd(format.initializationData, dvCsd));
  }
}
