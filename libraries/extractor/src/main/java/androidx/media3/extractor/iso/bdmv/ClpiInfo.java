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

public final class ClpiInfo {

  /**
   * Clip name (5-digit, e.g. "00001").
   */
  public final String clipName;

  /**
   * Streams described in this clip.
   */
  public final List<StreamInfo> streams;

  /**
   * EP map entries for accurate seeking (sorted by PTS).
   */
  public final List<EpMapEntry> epMap;

  /**
   * Clip duration in 45 kHz ticks (from STC sequence).
   */
  public final long durationTicks;

  /**
   * PCR PID for the primary STC sequence.
   */
  public final int pcrPid;

  /**
   * STC presentation_start_time in 45 kHz ticks.
   * MPLS in_time/out_time are absolute presentation timestamps that match EP map PTS directly;
   * this field does not need to be added to MPLS times for EP map lookups.
   */
  public final long stcStartTime;

  /**
   * SPN (source packet number) at the start of each STC sequence, in order.
   * Used by BdmvTsExtractor to determine which STC sequence an EP map entry belongs to.
   * Each value is the {@code spn_stc_start} field from the corresponding STC sequence entry.
   */
  public final long[] spnStcStarts;

  /**
   * Presentation start time (45 kHz ticks) for each STC sequence, in order.
   * Used together with {@link #spnStcStarts} to compute accurate seek times across STC boundaries.
   * Each value is the {@code presentation_start_time} field from the corresponding STC sequence.
   */
  public final long[] stcPresentationStartTimes;

  /**
   * Presentation end time (45 kHz ticks) for each STC sequence, in order.
   * Used together with {@link #stcPresentationStartTimes} to accumulate durations across STC
   * boundaries when building the EP seek map.
   */
  public final long[] stcPresentationEndTimes;

  public ClpiInfo(String clipName, List<StreamInfo> streams, List<EpMapEntry> epMap, long durationTicks, int pcrPid, long stcStartTime, long[] spnStcStarts, long[] stcPresentationStartTimes, long[] stcPresentationEndTimes) {
    this.clipName = clipName;
    this.streams = streams;
    this.epMap = epMap;
    this.durationTicks = durationTicks;
    this.pcrPid = pcrPid;
    this.stcStartTime = stcStartTime;
    this.spnStcStarts = spnStcStarts;
    this.stcPresentationStartTimes = stcPresentationStartTimes;
    this.stcPresentationEndTimes = stcPresentationEndTimes;
  }

  /**
   * Duration in microseconds.
   */
  public long getDurationUs() {
    return Util.scaleLargeTimestamp(durationTicks, C.MICROS_PER_SECOND, BdmvConstants.BDMV_TICKS_PER_SECOND);
  }
}
