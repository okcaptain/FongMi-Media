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

public final class BdmvConstants {

  public static final int TS_PACKET_SIZE = 188;
  public static final int M2TS_PACKET_SIZE = 192;
  public static final long BDMV_TICKS_PER_SECOND = 45000L;

  public static final int STREAM_GROUP_SEC_AUDIO = 5;
  public static final int STREAM_GROUP_SEC_VIDEO = 6;

  public static final int STREAM_TYPE_HEVC = 0x24;
  public static final int STREAM_TYPE_AVC = 0x1B;
  public static final int STREAM_TYPE_VC1 = 0xEA;
  public static final int STREAM_TYPE_MPEG2 = 0x02;

  public static final int DYNAMIC_RANGE_SDR = 0;
  public static final int DYNAMIC_RANGE_HDR10 = 1;
  public static final int DYNAMIC_RANGE_DOLBY_VISION = 2;
  public static final int DYNAMIC_RANGE_HLG = 3;
  public static final int DYNAMIC_RANGE_HDR10_PLUS = 4;

  static boolean isAudioStreamType(int streamType) {
    return streamType == 0x03 || streamType == 0x04 || (streamType >= 0x80 && streamType <= 0x86) || streamType == 0xa1 || streamType == 0xa2;
  }

  static boolean isVideoStreamType(int streamType) {
    return streamType == STREAM_TYPE_HEVC || streamType == STREAM_TYPE_AVC;
  }

  static boolean isSubtitle90StreamType(int streamType) {
    return streamType == 0x90 || streamType == 0x91 || streamType == 0xa0;
  }

  static boolean isSubtitle92StreamType(int streamType) {
    return streamType == 0x92;
  }

  static String parseLang(byte[] data, int off) {
    StringBuilder sb = new StringBuilder(3);
    for (int i = 0; i < 3; i++) {
      char c = (char) (data[off + i] & 0xFF);
      if (c == 0) {
        break;
      }
      sb.append(c);
    }
    return sb.toString();
  }
}
