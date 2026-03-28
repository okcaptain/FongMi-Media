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
package androidx.media3.extractor.dsd;

import androidx.annotation.NonNull;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.SeekPoint;
import androidx.media3.extractor.iso.IsoConstants;

final class SacdSectorSeekMap implements SeekMap {

  private final long totalSectors;
  private final long durationUs;

  SacdSectorSeekMap(long totalSectors, long durationUs) {
    this.totalSectors = totalSectors;
    this.durationUs = durationUs;
  }

  @Override
  public boolean isSeekable() {
    return true;
  }

  @Override
  public long getDurationUs() {
    return durationUs;
  }

  @NonNull
  @Override
  public SeekPoints getSeekPoints(long timeUs) {
    long clamped = Math.max(0, Math.min(timeUs, durationUs));
    long sector = durationUs > 0 ? clamped * totalSectors / durationUs : 0;
    long pos = sector * IsoConstants.SECTOR_SIZE;
    long snapUs = totalSectors > 0 ? sector * durationUs / totalSectors : 0;
    return new SeekPoints(new SeekPoint(snapUs, pos));
  }
}
