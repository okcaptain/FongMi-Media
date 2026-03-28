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
package androidx.media3.exoplayer.source.iso;

import androidx.media3.common.C;
import androidx.media3.common.MediaTitle;
import androidx.media3.extractor.iso.bdmv.BdmvStructure;
import androidx.media3.extractor.iso.bdmv.PlayItem;
import androidx.media3.extractor.iso.bdmv.Playlist;
import androidx.media3.extractor.iso.dvd.DvdStructure;
import androidx.media3.extractor.iso.dvd.DvdTitle;
import androidx.media3.extractor.iso.sacd.SacdStructure;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class IsoTitleScanner {

  static List<MediaTitle> buildDvdTitlesFromStructure(DvdStructure dvd) {
    List<MediaTitle> result = new ArrayList<>(dvd.titles.size());
    for (int i = 0; i < dvd.titles.size(); i++) {
      DvdTitle title = dvd.titles.get(i);
      long durationUs = title.getTotalDurationUs();
      if (durationUs <= 0) {
        continue;
      }
      result.add(new MediaTitle(i, durationUs, title.chapterTimesUs.length, title == dvd.mainTitle, MediaTitle.TYPE_DVD, "Title " + (i + 1), title == dvd.mainTitle));
    }
    Collections.sort(result, (a, b) -> Long.compare(b.durationUs, a.durationUs));
    return result;
  }

  static List<MediaTitle> buildBlurayTitlesFromStructure(BdmvStructure bdmv) {
    List<MediaTitle> result = new ArrayList<>(bdmv.allPlaylists.size());
    for (int i = 0; i < bdmv.allPlaylists.size(); i++) {
      Playlist playlist = bdmv.allPlaylists.get(i);
      long totalDurationUs = 0;
      for (PlayItem item : playlist.playItems) {
        totalDurationUs += item.getDurationUs();
      }
      if (totalDurationUs < C.MICROS_PER_SECOND * 60) {
        continue;
      }
      result.add(new MediaTitle(i, totalDurationUs, playlist.chapterMarks.size(), playlist == bdmv.mainPlaylist, MediaTitle.TYPE_BLURAY, "Playlist " + playlist.name, playlist == bdmv.mainPlaylist));
    }
    return result;
  }

  static List<MediaTitle> buildSacdTitlesFromStructure(SacdStructure sacd) {
    return SacdSourceHelper.buildTitles(sacd);
  }
}
