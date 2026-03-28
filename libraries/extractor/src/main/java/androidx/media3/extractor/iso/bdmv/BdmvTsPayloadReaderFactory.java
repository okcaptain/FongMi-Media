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

import android.util.SparseArray;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.ts.Ac3Reader;
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory;
import androidx.media3.extractor.ts.DtsReader;
import androidx.media3.extractor.ts.LpcmReader;
import androidx.media3.extractor.ts.PesReader;
import androidx.media3.extractor.ts.TrueHdReader;
import androidx.media3.extractor.ts.TsPayloadReader;
import androidx.media3.extractor.ts.Vc1Reader;
import com.google.common.collect.ImmutableList;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;

public final class BdmvTsPayloadReaderFactory implements TsPayloadReader.Factory {

  private static final int STREAM_TYPE_HDMV_LPCM = 0x80;
  private static final int STREAM_TYPE_BDMV_DTS = 0x82;
  private static final int STREAM_TYPE_BDMV_AC3_TRUE_HD = 0x83;
  private static final int STREAM_TYPE_BDMV_EAC3 = 0x84;
  private static final int STREAM_TYPE_BDMV_DTS_HD = 0x85;
  private static final int STREAM_TYPE_BDMV_DTS_HD_MA = 0x86;
  private static final int STREAM_TYPE_BDMV_EAC3_SEC = 0xA1;
  private static final int STREAM_TYPE_BDMV_DTS_EXPRESS_SEC = 0xA2;
  private static final int STREAM_TYPE_BDMV_VC1 = 0xEA;

  private static final int DESCRIPTOR_TAG_DOVI = 0xB0;

  private final DefaultTsPayloadReaderFactory delegate;
  private final Map<Integer, Queue<String>> langQueues;
  private final Map<Integer, Integer> pidDynamicRangeTypes;
  private int dvMdCompression;
  private int dvBlCompatId = -1;
  private int dvProfile;
  private int dvLevel = -1;

  public BdmvTsPayloadReaderFactory(@Nullable ClpiInfo clpi) {
    delegate = new DefaultTsPayloadReaderFactory(DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS | DefaultTsPayloadReaderFactory.FLAG_IGNORE_SPLICE_INFO_STREAM, ImmutableList.of());
    langQueues = buildLanguageQueues(clpi);
    pidDynamicRangeTypes = buildPidDynamicRangeTypes(clpi);
    dvProfile = inferDvProfileFromClpi(clpi);
  }

  private static Map<Integer, Integer> buildPidDynamicRangeTypes(@Nullable ClpiInfo clpi) {
    Map<Integer, Integer> map = new HashMap<>();
    if (clpi != null) {
      for (StreamInfo s : clpi.streams) {
        if (s.dynamicRangeType > 0) {
          map.put(s.pid, s.dynamicRangeType);
        }
      }
    }
    return map;
  }

  private static int inferDvProfileFromClpi(@Nullable ClpiInfo clpi) {
    if (clpi == null) {
      return -1;
    }
    boolean hasDvStream = false;
    int blStreamType = 0;
    for (StreamInfo s : clpi.streams) {
      if (BdmvConstants.isVideoStreamType(s.streamType)) {
        if (s.dynamicRangeType == BdmvConstants.DYNAMIC_RANGE_DOLBY_VISION) {
          hasDvStream = true;
        } else if (blStreamType == 0) {
          blStreamType = s.streamType;
        }
      }
    }
    if (hasDvStream && blStreamType != 0) {
      return (blStreamType == BdmvConstants.STREAM_TYPE_AVC) ? 4 : 7;
    }
    return -1;
  }

  private static Map<Integer, Queue<String>> buildLanguageQueues(@Nullable ClpiInfo clpi) {
    Map<Integer, Queue<String>> queues = new HashMap<>();
    if (clpi != null) {
      for (StreamInfo s : clpi.streams) {
        if (!s.languageCode.isEmpty()) {
          queues.computeIfAbsent(s.streamType, k -> new ArrayDeque<>()).add(s.languageCode);
        }
      }
    }
    return queues;
  }

  @Nullable
  private static TsPayloadReader createBdmvReader(int streamType, @Nullable String lang) {
    switch (streamType) {
      case STREAM_TYPE_HDMV_LPCM:
        return new PesReader(new LpcmReader(lang, MimeTypes.VIDEO_MP2T));
      case STREAM_TYPE_BDMV_AC3_TRUE_HD:
        return new PesReader(new TrueHdReader(lang, MimeTypes.VIDEO_MP2T));
      case STREAM_TYPE_BDMV_EAC3:
      case STREAM_TYPE_BDMV_EAC3_SEC:
        return new PesReader(new Ac3Reader(lang, 0, MimeTypes.VIDEO_MP2T));
      case STREAM_TYPE_BDMV_DTS:
      case STREAM_TYPE_BDMV_DTS_HD:
      case STREAM_TYPE_BDMV_DTS_HD_MA:
      case STREAM_TYPE_BDMV_DTS_EXPRESS_SEC:
        return new PesReader(new DtsReader(lang, 0, 4096, MimeTypes.VIDEO_MP2T));
      case STREAM_TYPE_BDMV_VC1:
        return new PesReader(new Vc1Reader(MimeTypes.VIDEO_MP2T));
      default:
        return null;
    }
  }

  public int getDynamicRangeTypeForPid(int pid) {
    Integer type = pidDynamicRangeTypes.get(pid);
    return type != null ? type : 0;
  }

  @NonNull
  @Override
  public SparseArray<TsPayloadReader> createInitialPayloadReaders() {
    return new SparseArray<>();
  }

  public int getDvMdCompression() {
    return dvMdCompression;
  }

  public int getDvBlCompatId() {
    return dvBlCompatId;
  }

  public int getDvProfile() {
    return dvProfile;
  }

  public int getDvLevel() {
    return dvLevel;
  }

  public boolean isDualLayerDv() {
    return dvProfile == 4 || dvProfile == 7;
  }

  @Override
  @Nullable
  public TsPayloadReader createPayloadReader(int streamType, @NonNull TsPayloadReader.EsInfo esInfo) {
    if (streamType == BdmvConstants.STREAM_TYPE_HEVC || streamType == BdmvConstants.STREAM_TYPE_AVC) {
      parseDolbyVisionDescriptor(esInfo.descriptorBytes);
    }
    String lang = resolveLanguage(streamType, esInfo);
    TsPayloadReader bdmvReader = createBdmvReader(streamType, lang);
    if (bdmvReader != null) {
      return bdmvReader;
    }
    if (lang != null && (esInfo.language == null || esInfo.language.isEmpty())) {
      esInfo = new TsPayloadReader.EsInfo(esInfo.streamType, lang, esInfo.audioType, esInfo.dvbSubtitleInfos.isEmpty() ? null : esInfo.dvbSubtitleInfos, esInfo.descriptorBytes);
    }
    return delegate.createPayloadReader(streamType, esInfo);
  }

  @Nullable
  private String resolveLanguage(int streamType, TsPayloadReader.EsInfo esInfo) {
    if (esInfo.language != null && !esInfo.language.isEmpty()) {
      return esInfo.language;
    }
    Queue<String> queue = langQueues.get(streamType);
    return queue != null ? queue.poll() : null;
  }

  private void parseDolbyVisionDescriptor(byte[] descriptorBytes) {
    ParsableByteArray data = new ParsableByteArray(descriptorBytes);
    while (data.bytesLeft() >= 2) {
      int tag = data.readUnsignedByte();
      int length = data.readUnsignedByte();
      if (length > data.bytesLeft()) {
        break;
      }
      if (tag == DESCRIPTOR_TAG_DOVI && length >= 4) {
        data.skipBytes(2);
        int profileAndLevel = data.readUnsignedShort();
        int parsedProfile = (profileAndLevel >> 9) & 0x7F;
        int parsedLevel = (profileAndLevel >> 3) & 0x3F;
        if (parsedProfile > 0) {
          dvProfile = parsedProfile;
        }
        if (parsedLevel > 0) {
          dvLevel = parsedLevel;
        }
        if (length >= 5) {
          int flagsByte = data.readUnsignedByte();
          dvBlCompatId = (flagsByte >> 4) & 0xF;
          dvMdCompression = (flagsByte >> 2) & 0x3;
        }
        return;
      }
      data.skipBytes(length);
    }
  }
}
