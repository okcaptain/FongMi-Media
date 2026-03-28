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

import androidx.media3.common.CacheDataReader;
import androidx.media3.extractor.iso.sacd.SacdTocParser;
import androidx.media3.extractor.iso.udf.UdfFileSystem;
import java.io.IOException;

final class IsoUtil {

  static final String[] BDMV_INDEX_PATHS = {"BDMV/index.bdmv", "BDMV/INDEX.BDM"};

  static boolean isBluray(UdfFileSystem udf) {
    for (String path : BDMV_INDEX_PATHS) {
      try {
        if (udf.findFile(path) != null) {
          return true;
        }
      } catch (IOException ignored) {
      }
    }
    return false;
  }

  static boolean isSacd(CacheDataReader isoReader) {
    try {
      return SacdTocParser.isSacd(isoReader);
    } catch (IOException ignored) {
      return false;
    }
  }
}
