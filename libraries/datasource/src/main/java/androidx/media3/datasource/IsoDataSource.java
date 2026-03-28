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
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import java.io.IOException;

public final class IsoDataSource extends BaseDataSource {

  private static final int TS_PACKET_SIZE = 188;
  private static final int M2TS_PACKET_SIZE = 192;
  private static final int M2TS_HEADER_SIZE = M2TS_PACKET_SIZE - TS_PACKET_SIZE;

  private static final int SACD_SECTOR_SIZE = 2048;
  private static final int SACD_HEADER_SIZE = 4;
  private static final int SACD_PAYLOAD_SIZE = SACD_SECTOR_SIZE - SACD_HEADER_SIZE;

  private final byte[] m2tsBuf = new byte[M2TS_PACKET_SIZE];
  private final byte[] sacdBuf = new byte[SACD_SECTOR_SIZE];
  private final DataSource upstream;
  private final long clipByteOffset;
  private final long clipByteLength;
  private final boolean stripM2tsHeaders;
  private final boolean stripSacdHeaders;

  @Nullable
  private Uri uri;
  private long bytesRemaining;
  private int m2tsBufPos;
  private int m2tsBufLimit;
  private int sacdBufPos;
  private int sacdBufLimit;

  public IsoDataSource(DataSource upstream, long clipByteOffset, long clipByteLength, boolean stripM2tsHeaders, boolean stripSacdHeaders) {
    super(false);
    this.upstream = upstream;
    this.clipByteOffset = clipByteOffset;
    this.clipByteLength = clipByteLength;
    this.stripM2tsHeaders = stripM2tsHeaders;
    this.stripSacdHeaders = stripSacdHeaders;
  }

  @Override
  public long open(DataSpec dataSpec) throws IOException {
    uri = dataSpec.uri;
    long clipPosition = dataSpec.position;
    long clipVirtualLength;
    if (stripM2tsHeaders) {
      clipVirtualLength = (clipByteLength / M2TS_PACKET_SIZE) * TS_PACKET_SIZE;
    } else if (stripSacdHeaders) {
      clipVirtualLength = (clipByteLength / SACD_SECTOR_SIZE) * SACD_PAYLOAD_SIZE;
    } else {
      clipVirtualLength = clipByteLength;
    }
    if (clipPosition > clipVirtualLength) {
      throw new DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE);
    }
    long isoOffset;
    long upstreamLength;
    long packetOffset = 0;
    if (stripM2tsHeaders) {
      long packetIndex = clipPosition / TS_PACKET_SIZE;
      packetOffset = clipPosition % TS_PACKET_SIZE;
      isoOffset = clipByteOffset + packetIndex * M2TS_PACKET_SIZE;
      long virtualRemaining = clipVirtualLength - clipPosition;
      bytesRemaining = dataSpec.length == C.LENGTH_UNSET ? virtualRemaining : Math.min(dataSpec.length, virtualRemaining);
      long packetsNeeded = (bytesRemaining + packetOffset + TS_PACKET_SIZE - 1) / TS_PACKET_SIZE;
      upstreamLength = packetsNeeded * M2TS_PACKET_SIZE;
    } else if (stripSacdHeaders) {
      long sectorIndex = clipPosition / SACD_PAYLOAD_SIZE;
      packetOffset = clipPosition % SACD_PAYLOAD_SIZE;
      isoOffset = clipByteOffset + sectorIndex * SACD_SECTOR_SIZE;
      long virtualRemaining = clipVirtualLength - clipPosition;
      bytesRemaining = dataSpec.length == C.LENGTH_UNSET ? virtualRemaining : Math.min(dataSpec.length, virtualRemaining);
      long sectorsNeeded = (bytesRemaining + packetOffset + SACD_PAYLOAD_SIZE - 1) / SACD_PAYLOAD_SIZE;
      upstreamLength = sectorsNeeded * SACD_SECTOR_SIZE;
    } else {
      isoOffset = clipByteOffset + clipPosition;
      long rawRemaining = clipVirtualLength - clipPosition;
      bytesRemaining = dataSpec.length == C.LENGTH_UNSET ? rawRemaining : Math.min(dataSpec.length, rawRemaining);
      upstreamLength = bytesRemaining;
    }
    m2tsBufPos = 0;
    m2tsBufLimit = 0;
    sacdBufPos = 0;
    sacdBufLimit = 0;
    DataSpec upstreamSpec = new DataSpec.Builder().setUri(dataSpec.uri).setPosition(isoOffset).setLength(upstreamLength).setKey(dataSpec.key).build();
    transferInitializing(dataSpec);
    upstream.open(upstreamSpec);
    transferStarted(dataSpec);
    if (stripM2tsHeaders && packetOffset > 0) {
      int got = readFully(m2tsBuf, 0, M2TS_PACKET_SIZE);
      if (got == M2TS_PACKET_SIZE) {
        m2tsBufPos = M2TS_HEADER_SIZE + (int) packetOffset;
        m2tsBufLimit = M2TS_PACKET_SIZE;
      }
    } else if (stripSacdHeaders && packetOffset > 0) {
      int got = readFully(sacdBuf, 0, SACD_SECTOR_SIZE);
      if (got == SACD_SECTOR_SIZE) {
        sacdBufPos = SACD_HEADER_SIZE + (int) packetOffset;
        sacdBufLimit = SACD_SECTOR_SIZE;
      }
    }
    return bytesRemaining;
  }

  @Override
  public int read(byte[] buffer, int offset, int length) throws IOException {
    if (length == 0) {
      return 0;
    }
    if (bytesRemaining == 0) {
      return C.RESULT_END_OF_INPUT;
    }
    int toRead = (int) Math.min(length, bytesRemaining);
    int totalRead = 0;
    if (stripM2tsHeaders) {
      while (totalRead < toRead) {
        if (m2tsBufPos >= m2tsBufLimit) {
          int got = readFully(m2tsBuf, 0, M2TS_PACKET_SIZE);
          if (got < M2TS_PACKET_SIZE) {
            if (bytesRemaining > TS_PACKET_SIZE) {
              throw new IOException("Unexpected end of M2TS stream: " + bytesRemaining + " bytes remaining");
            }
            break;
          }
          m2tsBufPos = M2TS_HEADER_SIZE;
          m2tsBufLimit = M2TS_PACKET_SIZE;
        }
        int available = m2tsBufLimit - m2tsBufPos;
        int copy = Math.min(toRead - totalRead, available);
        System.arraycopy(m2tsBuf, m2tsBufPos, buffer, offset + totalRead, copy);
        m2tsBufPos += copy;
        totalRead += copy;
      }
    } else if (stripSacdHeaders) {
      while (totalRead < toRead) {
        if (sacdBufPos >= sacdBufLimit) {
          int got = readFully(sacdBuf, 0, SACD_SECTOR_SIZE);
          if (got < SACD_SECTOR_SIZE) {
            break;
          }
          sacdBufPos = SACD_HEADER_SIZE;
          sacdBufLimit = SACD_SECTOR_SIZE;
        }
        int available = sacdBufLimit - sacdBufPos;
        int copy = Math.min(toRead - totalRead, available);
        System.arraycopy(sacdBuf, sacdBufPos, buffer, offset + totalRead, copy);
        sacdBufPos += copy;
        totalRead += copy;
      }
    } else {
      totalRead = upstream.read(buffer, offset, toRead);
      if (totalRead == C.RESULT_END_OF_INPUT) {
        totalRead = 0;
      }
    }
    if (totalRead > 0) {
      bytesRemaining -= totalRead;
      bytesTransferred(totalRead);
    }
    return totalRead == 0 ? C.RESULT_END_OF_INPUT : totalRead;
  }

  @Nullable
  @Override
  public Uri getUri() {
    return uri;
  }

  @Override
  public void close() throws IOException {
    uri = null;
    try {
      upstream.close();
    } finally {
      transferEnded();
    }
  }

  private int readFully(byte[] buf, int offset, int length) throws IOException {
    int total = 0;
    while (total < length) {
      int read = upstream.read(buf, offset + total, length - total);
      if (read == C.RESULT_END_OF_INPUT) {
        break;
      }
      total += read;
    }
    return total;
  }

  public static final class Factory implements DataSource.Factory {

    private final DataSource.Factory upstreamFactory;
    private final long byteOffset;
    private final long byteLength;
    private final boolean stripM2tsHeaders;
    private final boolean stripSacdHeaders;

    public Factory(DataSource.Factory upstreamFactory, long byteOffset, long byteLength, boolean stripM2tsHeaders) {
      this(upstreamFactory, byteOffset, byteLength, stripM2tsHeaders, false);
    }

    public Factory(DataSource.Factory upstreamFactory, long byteOffset, long byteLength, boolean stripM2tsHeaders, boolean stripSacdHeaders) {
      this.upstreamFactory = upstreamFactory;
      this.byteOffset = byteOffset;
      this.byteLength = byteLength;
      this.stripM2tsHeaders = stripM2tsHeaders;
      this.stripSacdHeaders = stripSacdHeaders;
    }

    @Override
    public DataSource createDataSource() {
      return new IsoDataSource(upstreamFactory.createDataSource(), byteOffset, byteLength, stripM2tsHeaders, stripSacdHeaders);
    }
  }
}
