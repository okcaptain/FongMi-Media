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
package androidx.media3.extractor.iso;

/**
 * Shared constants for ISO 9660 / UDF disc image parsing.
 */
public final class IsoConstants {

  /**
   * Logical sector size for ISO 9660 and UDF disc images, in bytes. ECMA-119 §6.1.2.
   */
  public static final int SECTOR_SIZE = 2048;
}
