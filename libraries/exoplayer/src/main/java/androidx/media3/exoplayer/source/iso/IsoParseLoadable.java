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

import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaTitle;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.IsoDataReader;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.upstream.Loader;
import androidx.media3.extractor.iso.bdmv.BdmvStructure;
import androidx.media3.extractor.iso.dvd.DvdStructure;
import androidx.media3.extractor.iso.sacd.SacdStructure;
import androidx.media3.extractor.iso.udf.UdfFileSystem;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

final class IsoParseLoadable implements Loader.Loadable {

  private final Uri isoUri;
  private final MediaItem mediaItem;
  private final DataSource.Factory dataSourceFactory;
  private final AtomicBoolean canceled = new AtomicBoolean();

  @Nullable
  MediaSource result;
  @Nullable
  List<MediaTitle> titles;

  IsoParseLoadable(MediaItem mediaItem, DataSource.Factory dataSourceFactory, Uri isoUri) {
    this.dataSourceFactory = dataSourceFactory;
    this.mediaItem = mediaItem;
    this.isoUri = isoUri;
  }

  @Override
  public void cancelLoad() {
    canceled.set(true);
  }

  @Override
  public void load() throws IOException {
    try (IsoDataReader isoReader = new IsoDataReader(dataSourceFactory, isoUri)) {
      if (IsoUtil.isSacd(isoReader)) {
        SacdStructure sacd = SacdSourceHelper.parseStructure(isoReader);
        if (canceled.get()) {
          return;
        }
        int titleIndex = IsoMediaSource.parseTitleIndex(isoUri);
        titles = IsoTitleScanner.buildSacdTitlesFromStructure(sacd);
        result = SacdSourceHelper.buildSource(mediaItem, dataSourceFactory, isoUri, titleIndex, sacd);
        return;
      }
      UdfFileSystem udf = new UdfFileSystem();
      udf.open(isoReader);
      if (canceled.get()) {
        return;
      }
      int titleIndex = IsoMediaSource.parseTitleIndex(isoUri);
      if (IsoUtil.isBluray(udf)) {
        if (titleIndex < 0) {
          BdmvStructure bdmv = BdmvSourceHelper.parseBdmv(isoReader, udf);
          if (canceled.get()) {
            return;
          }
          titles = IsoTitleScanner.buildBlurayTitlesFromStructure(bdmv);
          result = BdmvSourceHelper.buildSourceFromStructure(mediaItem, dataSourceFactory, isoUri, udf, isoReader, titleIndex, bdmv);
        } else {
          result = BdmvSourceHelper.buildSource(mediaItem, dataSourceFactory, isoUri, udf, isoReader, titleIndex);
        }
      } else {
        if (titleIndex < 0) {
          DvdStructure dvd = DvdSourceHelper.parseStructure(isoReader, udf);
          if (canceled.get()) {
            return;
          }
          titles = IsoTitleScanner.buildDvdTitlesFromStructure(dvd);
          result = DvdSourceHelper.buildSource(mediaItem, dataSourceFactory, isoUri, isoReader, udf, titleIndex, dvd);
        } else {
          result = DvdSourceHelper.buildSource(mediaItem, dataSourceFactory, isoUri, isoReader, udf, titleIndex);
        }
      }
    }
  }
}
