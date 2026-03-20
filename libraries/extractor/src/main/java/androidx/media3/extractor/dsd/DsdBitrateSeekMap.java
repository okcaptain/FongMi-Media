/*
 * Copyright (C) 2024 The Android Open Source Project
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
import androidx.media3.common.C;
import androidx.media3.extractor.SeekMap;
import androidx.media3.extractor.SeekPoint;

final class DsdBitrateSeekMap implements SeekMap {

  private final long durationUs;
  private final long bytesPerSecond;
  private final long alignUnit;
  private final long dataStart;
  private final long dataEnd;
  private final boolean seekable;

  DsdBitrateSeekMap(long durationUs, long bytesPerSecond, long alignUnit, long dataStart, long dataEnd, boolean seekable) {
    this.durationUs = durationUs;
    this.bytesPerSecond = bytesPerSecond;
    this.alignUnit = alignUnit;
    this.dataStart = dataStart;
    this.dataEnd = dataEnd;
    this.seekable = seekable;
  }

  @Override
  public boolean isSeekable() {
    return seekable;
  }

  @Override
  public long getDurationUs() {
    return durationUs;
  }

  @NonNull
  @Override
  public SeekPoints getSeekPoints(long timeUs) {
    if (!seekable || bytesPerSecond == 0) {
      return new SeekPoints(new SeekPoint(0, dataStart));
    }
    long clampedUs = Math.max(0, Math.min(timeUs, durationUs));
    long targetByte = clampedUs * bytesPerSecond / C.MICROS_PER_SECOND;
    targetByte = (targetByte / alignUnit) * alignUnit;
    long byteOffset = dataStart + targetByte;
    if (dataEnd != C.LENGTH_UNSET) {
      byteOffset = Math.min(byteOffset, dataEnd);
    }
    long actualTimeUs = targetByte * C.MICROS_PER_SECOND / bytesPerSecond;
    return new SeekPoints(new SeekPoint(actualTimeUs, byteOffset));
  }
}
