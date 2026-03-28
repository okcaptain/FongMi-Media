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

public final class MplsStreamEntry {

  /**
   * Stream group within the STN:
   * <ul>
   *   <li>1 = primary video
   *   <li>2 = primary audio
   *   <li>3 = presentation graphics (PG / subtitles)
   *   <li>4 = interactive graphics (IG)
   *   <li>5 = secondary audio
   *   <li>6 = secondary video
   * </ul>
   */
  public final int streamType;

  /**
   * Elementary stream PID.
   */
  public final int pid;

  /**
   * Coding type from the stream attributes block (matches CLPI stream coding type).
   * E.g. 0x1B = H.264, 0x24 = H.265/HEVC, 0x80 = LPCM, 0x81 = AC-3, 0x90 = PG subtitle.
   */
  public final int codingType;

  /**
   * 3-character ISO 639-2 language code (audio and subtitle streams only).
   * Empty string when not applicable (e.g. video streams).
   */
  public final String languageCode;

  public MplsStreamEntry(int streamType, int pid, int codingType, String languageCode) {
    this.streamType = streamType;
    this.pid = pid;
    this.codingType = codingType;
    this.languageCode = languageCode;
  }
}
