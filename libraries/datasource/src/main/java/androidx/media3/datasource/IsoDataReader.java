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
package androidx.media3.datasource;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.common.CacheDataReader;
import java.io.Closeable;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

public final class IsoDataReader implements CacheDataReader, Closeable {

  private static final int SECTOR_SIZE = 2048;
  private static final int CACHE_SECTORS = 8192;
  private static final int PREFETCH_SECTORS = 64;
  private static final long LENGTH_UNRESOLVED = -2;
  private static final long LENGTH_UNKNOWN = -1;

  private final Uri uri;
  private final DataSource.Factory dataSourceFactory;

  private final LinkedHashMap<Long, byte[]> sectorCache = new LinkedHashMap<Long, byte[]>(CACHE_SECTORS * 2, 0.75f, true) {
    @Override
    protected boolean removeEldestEntry(Map.Entry<Long, byte[]> eldest) {
      return size() > CACHE_SECTORS;
    }
  };

  private long cachedLength = LENGTH_UNRESOLVED;

  public IsoDataReader(DataSource.Factory dataSourceFactory, Uri uri) {
    this.dataSourceFactory = dataSourceFactory;
    this.uri = uri;
  }

  @Override
  public int read(long byteOffset, byte[] buf, int offset, int length) throws IOException {
    long firstSector = byteOffset / SECTOR_SIZE;
    long lastSector = (byteOffset + length - 1) / SECTOR_SIZE;
    int filled = 0;
    for (long s = firstSector; s <= lastSector; s++) {
      byte[] sector = getSector(s);
      int sectorStart = (int) (s == firstSector ? byteOffset % SECTOR_SIZE : 0);
      int sectorEnd = (int) (s == lastSector ? (byteOffset + length - 1) % SECTOR_SIZE + 1 : SECTOR_SIZE);
      int chunk = sectorEnd - sectorStart;
      System.arraycopy(sector, sectorStart, buf, offset + filled, chunk);
      filled += chunk;
    }
    return filled;
  }

  @Override
  public void prefetch(long byteOffset) {
    long sectorNum = byteOffset / SECTOR_SIZE;
    synchronized (sectorCache) {
      if (sectorCache.containsKey(sectorNum)) {
        return;
      }
    }
    try {
      getSector(sectorNum);
    } catch (IOException ignored) {
    }
  }

  @Override
  public void prefetchRange(long startByteOffset, long endByteOffset) {
    long firstSector = startByteOffset / SECTOR_SIZE;
    long lastSector = (endByteOffset - 1) / SECTOR_SIZE;
    synchronized (sectorCache) {
      boolean allCached = true;
      for (long s = firstSector; s <= lastSector; s++) {
        if (!sectorCache.containsKey(s)) {
          allCached = false;
          break;
        }
      }
      if (allCached) {
        return;
      }
    }
    int length = (int) ((lastSector - firstSector + 1) * SECTOR_SIZE);
    byte[] batch = new byte[length];
    try {
      int total = directRead(firstSector * SECTOR_SIZE, batch, 0, length);
      if (total <= 0) {
        return;
      }
      cacheSectors(firstSector, batch, total);
    } catch (IOException ignored) {
    }
  }

  private byte[] getSector(long sectorNum) throws IOException {
    synchronized (sectorCache) {
      byte[] cached = sectorCache.get(sectorNum);
      if (cached != null) {
        return cached;
      }
    }
    int batchSize = PREFETCH_SECTORS * SECTOR_SIZE;
    byte[] buf = new byte[batchSize];
    int total = directRead(sectorNum * SECTOR_SIZE, buf, 0, batchSize);
    if (total < SECTOR_SIZE) {
      throw new IOException("Read returned " + total + " at sector " + sectorNum);
    }
    cacheSectors(sectorNum, buf, total);
    synchronized (sectorCache) {
      byte[] result = sectorCache.get(sectorNum);
      if (result != null) {
        return result;
      }
      result = new byte[SECTOR_SIZE];
      System.arraycopy(buf, 0, result, 0, SECTOR_SIZE);
      sectorCache.put(sectorNum, result);
      return result;
    }
  }

  private void cacheSectors(long startSector, byte[] data, int bytesRead) {
    int sectorsRead = bytesRead / SECTOR_SIZE;
    synchronized (sectorCache) {
      for (int i = 0; i < sectorsRead; i++) {
        long key = startSector + i;
        if (!sectorCache.containsKey(key)) {
          byte[] sector = new byte[SECTOR_SIZE];
          System.arraycopy(data, i * SECTOR_SIZE, sector, 0, SECTOR_SIZE);
          sectorCache.put(key, sector);
        }
      }
    }
  }

  private int directRead(long byteOffset, byte[] buf, int offset, int length) throws IOException {
    DataSource dataSource = dataSourceFactory.createDataSource();
    DataSpec spec = new DataSpec.Builder().setUri(uri).setPosition(byteOffset).setLength(length).build();
    dataSource.open(spec);
    int total = 0;
    try {
      while (total < length) {
        int n = dataSource.read(buf, offset + total, length - total);
        if (n == C.RESULT_END_OF_INPUT) {
          break;
        }
        total += n;
      }
    } finally {
      dataSource.close();
    }
    return total == 0 ? -1 : total;
  }

  @Override
  public long length() throws IOException {
    if (cachedLength == LENGTH_UNRESOLVED) {
      DataSource dataSource = dataSourceFactory.createDataSource();
      DataSpec spec = new DataSpec.Builder().setUri(uri).build();
      try {
        long len = dataSource.open(spec);
        cachedLength = len > 0 ? len : LENGTH_UNKNOWN;
      } catch (IOException e) {
        cachedLength = LENGTH_UNKNOWN;
      } finally {
        dataSource.close();
      }
    }
    return cachedLength;
  }

  @Override
  public void close() {
    synchronized (sectorCache) {
      sectorCache.clear();
    }
  }
}
