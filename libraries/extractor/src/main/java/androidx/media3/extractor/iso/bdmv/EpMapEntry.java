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

import androidx.media3.common.C;

public final class EpMapEntry {

  /**
   * PTS in 45 kHz ticks.
   */
  public final long pts;

  /**
   * Byte offset within the M2TS file (spn * {@link BdmvConstants#M2TS_PACKET_SIZE}).
   */
  public final long byteOffset;

  /**
   * Whether this entry marks a point where the angle changes (is_angle_change_point bit in the
   * fine EP entry). When true, the entry is an angle change point for multi-angle clips.
   */
  public final boolean isAngleChangePoint;

  public EpMapEntry(long pts, long byteOffset, boolean isAngleChangePoint) {
    this.pts = pts;
    this.byteOffset = byteOffset;
    this.isAngleChangePoint = isAngleChangePoint;
  }

  /**
   * PTS converted to microseconds.
   */
  public long getPresentationTimeUs() {
    return pts * C.MICROS_PER_SECOND / BdmvConstants.BDMV_TICKS_PER_SECOND;
  }
}
