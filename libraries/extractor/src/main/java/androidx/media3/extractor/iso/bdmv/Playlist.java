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

import java.util.List;

public final class Playlist {

  /**
   * Playlist file name (5-digit, e.g. "00800").
   */
  public final String name;

  /**
   * Ordered list of play items.
   */
  public final List<PlayItem> playItems;

  /**
   * Chapter marks.
   */
  public final List<ChapterMark> chapterMarks;

  public Playlist(String name, List<PlayItem> playItems, List<ChapterMark> chapterMarks) {
    this.name = name;
    this.playItems = playItems;
    this.chapterMarks = chapterMarks;
  }
}

