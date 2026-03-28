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
import java.util.Map;

public final class BdmvStructure {

  /**
   * The main playlist selected for playback (highest score).
   */
  public final Playlist mainPlaylist;

  /**
   * All valid playlists sorted by score descending. The first element is the main playlist.
   */
  public final List<Playlist> allPlaylists;

  /**
   * Map from clip name (e.g. "00001") to its parsed CLPI data.
   */
  public final Map<String, ClpiInfo> clipInfos;

  public BdmvStructure(Playlist mainPlaylist, List<Playlist> allPlaylists, Map<String, ClpiInfo> clipInfos) {
    this.mainPlaylist = mainPlaylist;
    this.allPlaylists = allPlaylists;
    this.clipInfos = clipInfos;
  }
}
