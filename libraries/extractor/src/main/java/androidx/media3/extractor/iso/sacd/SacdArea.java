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
package androidx.media3.extractor.iso.sacd;

import androidx.media3.extractor.iso.IsoConstants;
import java.util.List;

public final class SacdArea {

  /**
   * Area type constant for the 2-channel stereo area.
   */
  public static final int TYPE_STEREO = 0;
  /**
   * Area type constant for the multi-channel (5.1) area.
   */
  public static final int TYPE_MULTI = 1;

  /**
   * frame_format lower nibble != 0: DSD_3_IN_14 or DSD_3_IN_16.
   */
  public static final int ENCODING_DSD = 0;
  /**
   * frame_format lower nibble == 0: FRAME_FORMAT_DST (compressed).
   */
  public static final int ENCODING_DST = 1;

  /**
   * Area type: {@link #TYPE_STEREO} or {@link #TYPE_MULTI}.
   */
  public final int type;
  /**
   * Audio encoding: {@link #ENCODING_DSD} or {@link #ENCODING_DST}.
   */
  public final int audioEncoding;
  /**
   * Number of audio channels in this area (2 or 6).
   */
  public final int channelCount;
  /**
   * Absolute ISO sector number where DSD audio begins (inclusive).
   */
  public final long audioStartSector;
  /**
   * Absolute ISO sector number where DSD audio ends (inclusive).
   */
  public final long audioEndSector;
  /**
   * Ordered list of tracks contained in this area.
   */
  public final List<SacdTrack> tracks;

  public SacdArea(int type, int audioEncoding, int channelCount, long audioStartSector, long audioEndSector, List<SacdTrack> tracks) {
    this.type = type;
    this.audioEncoding = audioEncoding;
    this.channelCount = channelCount;
    this.audioStartSector = audioStartSector;
    this.audioEndSector = audioEndSector;
    this.tracks = tracks;
  }

  public long trackStartByteOffset(SacdTrack track) {
    return track.startLsn * IsoConstants.SECTOR_SIZE;
  }

  public long trackByteLength(SacdTrack track) {
    return track.lengthLsn * IsoConstants.SECTOR_SIZE;
  }
}
