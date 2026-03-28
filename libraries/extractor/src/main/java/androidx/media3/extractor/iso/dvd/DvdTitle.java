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

import androidx.annotation.Nullable;
import androidx.media3.extractor.text.vobsub.VobsubParser;
import java.util.List;

public final class DvdTitle {

  /**
   * Cells making up this title, in order.
   */
  public final List<DvdCell> cells;

  /**
   * Language codes for audio streams, indexed by sub-stream index (0x80–0x87 → index 0–7).
   * Empty string means not specified. Length is always 8.
   */
  public final String[] audioLanguages;

  /**
   * Language codes for subpicture streams, indexed by sub-stream index (0x20–0x3F → index 0–31).
   * Empty string means not specified. Length is always 32.
   */
  public final String[] subpLanguages;

  /**
   * VobSub IDX initialisation data (UTF-8 text) containing CLUT palette and video resolution,
   * suitable for passing as {@code Format.initializationData[0]} to {@link
   * VobsubParser}. Null if not parsed.
   */
  @Nullable
  public final String vobsubIdx;

  /**
   * Audio format codes per stream (0=AC-3, 2=MPEG-1, 3=MPEG-2ext, 4=LPCM, 6=DTS, 7=SDDS).
   * Length is always 8; 0 for unused entries.
   */
  public final int[] audioFormats;

  /**
   * Channel count per audio stream (1–6). Length is always 8; 0 for unused entries.
   */
  public final int[] audioChannels;

  /**
   * Active audio sub-stream indices per PGC (values in range 0–7).
   * A value of -1 means not specified.  Length is always 8.
   */
  public final int[] activeAudioStreams;

  /**
   * Active subpicture sub-stream indices per PGC (values in range 0–31).
   * A value of -1 means not specified. Length is always 32.
   */
  public final int[] activeSubpStreams;

  /**
   * VOB-set-relative sector numbers for each VOBU in this title's PGC, in order.
   * Each entry corresponds to the start sector of one VOBU.
   * Used by DvdSourceHelper to build per-cell IndexSeekMap instances for accurate seeking.
   * May be empty if the VOBU address map was not available.
   */
  public final long[] vobuSectors;

  /**
   * Chapter start times in microseconds, derived from PGC program map.
   * {@code chapterTimesUs[0]} is always 0 (start of title).
   * {@code chapterTimesUs[i]} is the start time of chapter i (0-based).
   * May be empty if the program map was not available.
   */
  public final long[] chapterTimesUs;

  public DvdTitle(List<DvdCell> cells, String[] audioLanguages, String[] subpLanguages, @Nullable String vobsubIdx, int[] activeAudioStreams, int[] activeSubpStreams, long[] vobuSectors, long[] chapterTimesUs) {
    this(cells, audioLanguages, subpLanguages, vobsubIdx, new int[8], new int[8], activeAudioStreams, activeSubpStreams, vobuSectors, chapterTimesUs);
  }

  public DvdTitle(List<DvdCell> cells, String[] audioLanguages, String[] subpLanguages, @Nullable String vobsubIdx, int[] audioFormats, int[] audioChannels, int[] activeAudioStreams, int[] activeSubpStreams, long[] vobuSectors, long[] chapterTimesUs) {
    this.cells = cells;
    this.audioLanguages = audioLanguages;
    this.subpLanguages = subpLanguages;
    this.vobsubIdx = vobsubIdx;
    this.audioFormats = audioFormats;
    this.audioChannels = audioChannels;
    this.activeAudioStreams = activeAudioStreams;
    this.activeSubpStreams = activeSubpStreams;
    this.vobuSectors = vobuSectors;
    this.chapterTimesUs = chapterTimesUs;
  }

  /**
   * Total duration of all cells in microseconds.
   */
  public long getTotalDurationUs() {
    long total = 0;
    for (DvdCell cell : cells) {
      total += cell.durationUs;
    }
    return total;
  }
}
