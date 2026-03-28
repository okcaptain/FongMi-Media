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

public final class IsoFileEntry {

  // File name within the UDF directory.
  public final String name;

  // Byte offset of the file's data within the ISO image.
  public final long byteOffset;

  // Length of the file's data in bytes.
  public final long length;

  public IsoFileEntry(String name, long byteOffset, long length) {
    this.name = name;
    this.byteOffset = byteOffset;
    this.length = length;
  }
}
