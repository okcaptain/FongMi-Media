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
package androidx.media3.common;

import androidx.media3.common.util.UnstableApi;
import java.util.Objects;

/**
 * Metadata about one playable title inside a multi-title media container (e.g. DVD or Blu-ray ISO
 * image).
 *
 * <p>When a player parses a media source that contains more than one title, it reports the
 * available titles via {@link Player.Listener#onMediaTitlesChanged(java.util.List)}. The app can
 * then call {@link Player#getCurrentMediaTitles()} at any time to retrieve the list.
 */
@UnstableApi
public final class MediaTitle {

  /** Title type constant for a Blu-ray playlist. */
  public static final String TYPE_BLURAY = "bluray";

  /** Title type constant for a DVD title-set. */
  public static final String TYPE_DVD = "dvd";

  /** Title type constant for a SACD audio area. */
  public static final String TYPE_SACD = "sacd";

  /** Zero-based index used to select this title for playback. */
  public final int index;

  /** Total duration in microseconds. */
  public final long durationUs;

  /** Number of chapters (Blu-ray chapter marks or DVD programs). */
  public final int chapterCount;

  /** Whether this title was heuristically identified as the main feature. */
  public final boolean isMain;

  /** The container type. One of {@link #TYPE_BLURAY} or {@link #TYPE_DVD}, or a custom string for future formats. */
  public final String type;

  /** Human-readable label, e.g. {@code "Title 1"} or {@code "Playlist 00800"}. */
  public final String label;

  /**
   * Whether this title is currently selected for playback. Set to {@code true} when the player is
   * prepared with a URI that contains a {@code #title=N} fragment matching this title's index.
   */
  public final boolean selected;

  public MediaTitle(int index, long durationUs, int chapterCount, boolean isMain, String type, String label, boolean selected) {
    this.index = index;
    this.durationUs = durationUs;
    this.chapterCount = chapterCount;
    this.isMain = isMain;
    this.type = type;
    this.label = label;
    this.selected = selected;
  }

  /** Returns a copy of this title with {@link #selected} set to the given value. */
  public MediaTitle withSelected(boolean selected) {
    return new MediaTitle(index, durationUs, chapterCount, isMain, type, label, selected);
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof MediaTitle)) {
      return false;
    }
    MediaTitle other = (MediaTitle) o;
    return index == other.index
        && durationUs == other.durationUs
        && chapterCount == other.chapterCount
        && isMain == other.isMain
        && selected == other.selected
        && Objects.equals(type, other.type)
        && Objects.equals(label, other.label);
  }

  @Override
  public int hashCode() {
    return Objects.hash(index, durationUs, chapterCount, isMain, type, label, selected);
  }
}
