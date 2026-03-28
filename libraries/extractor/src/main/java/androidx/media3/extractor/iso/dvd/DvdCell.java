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
package androidx.media3.extractor.iso.dvd;

public final class DvdCell {

  /**
   * Byte offset of the VOB data for this cell within the ISO image.
   */
  public final long byteOffset;

  /**
   * Length of the VOB data in bytes.
   */
  public final long length;

  /**
   * Duration of this cell in microseconds.
   */
  public final long durationUs;

  /**
   * First VOB-set-relative sector number of this cell (from cell_playback_t.first_sector).
   * Used to correlate VOBU admap entries with this cell.
   */
  public final long firstSector;

  /**
   * Last VOB-set-relative sector number of this cell (from cell_playback_t.last_sector).
   * Used to determine which VOBU admap entries belong to this cell.
   */
  public final long lastSector;

  public DvdCell(long byteOffset, long length, long durationUs, long firstSector, long lastSector) {
    this.byteOffset = byteOffset;
    this.length = length;
    this.durationUs = durationUs;
    this.firstSector = firstSector;
    this.lastSector = lastSector;
  }
}
