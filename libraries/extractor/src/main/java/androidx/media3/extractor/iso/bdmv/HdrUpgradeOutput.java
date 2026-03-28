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

import androidx.annotation.NonNull;
import androidx.media3.common.C;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.ForwardingExtractorOutput;
import androidx.media3.extractor.TrackOutput;

final class HdrUpgradeOutput extends ForwardingExtractorOutput {

  private final BdmvTsPayloadReaderFactory payloadReaderFactory;

  HdrUpgradeOutput(ExtractorOutput output, BdmvTsPayloadReaderFactory payloadReaderFactory) {
    super(output);
    this.payloadReaderFactory = payloadReaderFactory;
  }

  @NonNull
  @Override
  public TrackOutput track(int id, @C.TrackType int type) {
    if (type == C.TRACK_TYPE_VIDEO) {
      int dynamicRangeType = payloadReaderFactory.getDynamicRangeTypeForPid(id);
      TrackOutput upstream = super.track(id, type);
      if (dynamicRangeType == 0) {
        return upstream;
      }
      return new HdrTrackOutput(upstream, dynamicRangeType, payloadReaderFactory);
    }
    return super.track(id, type);
  }
}
