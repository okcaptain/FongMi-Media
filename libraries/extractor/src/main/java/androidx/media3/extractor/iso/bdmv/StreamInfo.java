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

public final class StreamInfo {

  /**
   * PID of the stream.
   */
  public final int pid;

  /**
   * Stream type (e.g. 0x1B = H.264, 0xEA = VC-1, 0x80 = AC-3).
   */
  public final int streamType;

  /**
   * ISO 639 language code, or empty string if not applicable.
   */
  public final String languageCode;

  /**
   * HDR dynamic range type for H.265/HEVC streams (coding type 0x24) as defined in the BDMV spec.
   * 0 = SDR, 1 = HDR10, 2 = Dolby Vision, 3 = HLG, 4 = HDR10+. 0 for non-HEVC streams.
   */
  public final int dynamicRangeType;

  /**
   * Color space identifier for H.265/HEVC streams. 0 for non-HEVC streams.
   */
  public final int colorSpace;

  /**
   * HDR10+ flag for H.265/HEVC streams (1 = HDR10+ present). 0 for non-HEVC streams.
   */
  public final int hdrPlusFlag;

  public StreamInfo(int pid, int streamType, String languageCode, int dynamicRangeType, int colorSpace, int hdrPlusFlag) {
    this.pid = pid;
    this.streamType = streamType;
    this.languageCode = languageCode;
    this.dynamicRangeType = dynamicRangeType;
    this.colorSpace = colorSpace;
    this.hdrPlusFlag = hdrPlusFlag;
  }
}
