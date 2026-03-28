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
import androidx.media3.common.util.Util;
import java.util.List;

public final class PlayItem {

  /**
   * Clip name (5-digit ASCII, e.g. "00001"), used to locate the M2TS and CLPI files.
   */
  public final String clipName;

  /**
   * In-point in 45 kHz ticks.
   */
  public final long inTimeTicks;

  /**
   * Out-point in 45 kHz ticks.
   */
  public final long outTimeTicks;

  /**
   * Connection condition from the play item header (lower nibble of byte at play_item offset+12).
   * <ul>
   *   <li>1 = seamless playback continuation (same STC sequence, no discontinuity)
   *   <li>5 = seamless playback continuation (different STC sequence, no discontinuity)
   *   <li>6 = non-seamless (STC discontinuity; new STC sequence)
   * </ul>
   * Values 1 and 5 indicate that the clip follows the previous one without a timestamp reset.
   */
  public final int connectionCondition;

  /**
   * Stream entries from the Stream Table Network (STN) within this play item.
   * Contains authoritative PID, codec type, and language information for all streams.
   * May be empty if the MPLS file does not include STN data.
   */
  public final List<MplsStreamEntry> stnStreams;

  public PlayItem(String clipName, long inTimeTicks, long outTimeTicks, int connectionCondition, List<MplsStreamEntry> stnStreams) {
    this.clipName = clipName;
    this.inTimeTicks = inTimeTicks;
    this.outTimeTicks = outTimeTicks;
    this.connectionCondition = connectionCondition;
    this.stnStreams = stnStreams;
  }

  /**
   * Duration in microseconds.
   */
  public long getDurationUs() {
    return Util.scaleLargeTimestamp(outTimeTicks - inTimeTicks, C.MICROS_PER_SECOND, BdmvConstants.BDMV_TICKS_PER_SECOND);
  }
}
