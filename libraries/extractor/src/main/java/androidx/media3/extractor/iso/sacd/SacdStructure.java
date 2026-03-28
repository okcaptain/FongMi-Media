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
package androidx.media3.extractor.iso.sacd;

import androidx.annotation.Nullable;

public final class SacdStructure {

  /**
   * The stereo (2ch) area, or {@code null} if the disc has no stereo area.
   */
  @Nullable
  public final SacdArea stereoArea;

  /**
   * The multi-channel (e.g. 5.1ch) area, or {@code null} if the disc has no multi-channel area.
   */
  @Nullable
  public final SacdArea multiArea;

  public SacdStructure(@Nullable SacdArea stereoArea, @Nullable SacdArea multiArea) {
    this.stereoArea = stereoArea;
    this.multiArea = multiArea;
  }

  /**
   * Returns the preferred area to play. Multi-channel is preferred over stereo when both exist.
   *
   * @throws IllegalStateException if neither area is present
   */
  public SacdArea getPreferredArea() {
    if (multiArea != null) {
      return multiArea;
    }
    if (stereoArea != null) {
      return stereoArea;
    }
    throw new IllegalStateException("SACD has no playable area");
  }
}
