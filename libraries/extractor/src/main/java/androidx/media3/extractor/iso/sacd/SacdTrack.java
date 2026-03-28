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

public final class SacdTrack {

  public final int trackNumber;
  public final int channelCount;
  public final long startLsn;
  public final long lengthLsn;
  private final long durationUs;

  public SacdTrack(int trackNumber, int channelCount, long startLsn, long lengthLsn, long durationUs) {
    this.trackNumber = trackNumber;
    this.channelCount = channelCount;
    this.startLsn = startLsn;
    this.lengthLsn = lengthLsn;
    this.durationUs = durationUs;
  }

  public long getDurationUs() {
    return durationUs;
  }
}
