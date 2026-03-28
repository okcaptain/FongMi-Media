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
package androidx.media3.extractor.iso.udf;

import androidx.annotation.Nullable;
import androidx.media3.common.CacheDataReader;
import androidx.media3.common.util.Util;
import androidx.media3.extractor.iso.IsoConstants;
import androidx.media3.extractor.iso.IsoFileEntry;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class UdfFileSystem {

  private static final int SECTOR_SIZE = IsoConstants.SECTOR_SIZE;
  private static final int PD_PARTITION_START_OFFSET = 188;
  private static final int PD_ACCESS_TYPE_OFFSET = 22;

  private static final long AVDP_SECTOR_MAIN = 256L;
  private static final long AVDP_SECTOR_RESERVE = 512L;
  private static final int AVDP_MVDS_LENGTH_OFFSET = 16;
  private static final int AVDP_MVDS_LOCATION_OFFSET = 20;
  private static final int AVDP_RVDS_LENGTH_OFFSET = 24;
  private static final int AVDP_RVDS_LOCATION_OFFSET = 28;

  private static final int VDS_DEFAULT_SECTORS = 32;
  private static final int FSD_SCAN_SECTOR_COUNT = 64;
  private static final int FSD_ROOT_ICB_OFFSET = 404;

  private static final int LVD_NUM_PARTITION_MAPS_OFFSET = 268;
  private static final int LVD_PARTITION_MAPS_START = 440;
  private static final int PM_TYPE2_MIN_SIZE = 40;
  private static final int PM_TYPE2_ID_OFFSET = 5;
  private static final int PM_TYPE2_ID_LENGTH = 8;
  private static final int PM_META_FILOC_OFFSET = 40;

  private static final int FE_INFO_LENGTH_OFFSET = 56;
  private static final int FE_ICB_TAG_FLAGS_OFFSET = 34;
  private static final int FE_EA_LENGTH_OFFSET = 168;
  private static final int FE_AD_LENGTH_OFFSET = 172;
  private static final int FE_AD_START_OFFSET = 176;
  private static final int EFE_EA_LENGTH_OFFSET = 208;
  private static final int EFE_AD_LENGTH_OFFSET = 212;
  private static final int EFE_AD_START_OFFSET = 216;

  private static final int OSTA_CS0_UTF16BE = 16;

  private static final int TAG_FSD = 256;
  private static final int TAG_FID = 257;
  private static final int TAG_FE = 261;
  private static final int TAG_EFE = 266;
  private static final int TAG_AVDP = 2;
  private static final int TAG_PD = 5;
  private static final int TAG_LVD = 6;
  private static final int TAG_TD = 8;

  private static final int AD_LONG = 1;
  private static final int AD_EXTENDED = 2;
  private static final int AD_EMBEDDED = 3;

  private static final int AD_SHORT_SIZE = 8;
  private static final int AD_LONG_SIZE = 16;
  private static final int AD_EXTENDED_SIZE = 20;

  private static final int ENTRY_OFFSET = 0;
  private static final int ENTRY_SIZE = 1;
  private static final int ENTRY_IS_DIR = 2;

  private static final int FID_MIN_SIZE = 38;
  private static final int FID_FILE_CHARS_OFFSET = 18;
  private static final int FID_FI_LENGTH_OFFSET = 19;
  private static final int FID_ICB_LBN_OFFSET = 24;
  private static final int FID_IMPL_USE_LEN_OFFSET = 36;

  private final byte[] sectorBuf = new byte[SECTOR_SIZE];
  private final ByteBuffer sectorBB = ByteBuffer.wrap(sectorBuf).order(ByteOrder.LITTLE_ENDIAN);

  private CacheDataReader reader;
  private long metadataPartitionSectors;
  private long fsdPhysicalSector;
  private long partition0Base;
  private long rootIcbLbn;

  public void open(CacheDataReader reader) throws IOException {
    this.reader = reader;
    findFsdAndPartitionBase();
  }

  @Nullable
  public IsoFileEntry findFile(String path) throws IOException {
    String normalized = path.replace('\\', '/');
    int lastSlash = normalized.lastIndexOf('/');
    String dirPath = lastSlash > 0 ? normalized.substring(0, lastSlash) : "";
    String fileName = lastSlash >= 0 ? normalized.substring(lastSlash + 1) : normalized;
    if (fileName.isEmpty()) {
      return null;
    }
    long dirLbn;
    if (dirPath.isEmpty()) {
      dirLbn = rootIcbLbn;
    } else {
      dirLbn = findDirLbn(dirPath);
      if (dirLbn < 0) {
        return null;
      }
    }
    Map<String, long[]> entries = readDirectoryEntries(dirLbn);
    String nameLower = fileName.toLowerCase(Locale.US);
    for (Map.Entry<String, long[]> e : entries.entrySet()) {
      if (e.getKey().toLowerCase(Locale.US).equals(nameLower)) {
        long[] v = e.getValue();
        if (v[ENTRY_IS_DIR] != 0) {
          return null;
        }
        return v[ENTRY_OFFSET] >= 0 ? new IsoFileEntry(e.getKey(), v[ENTRY_OFFSET], v[ENTRY_SIZE]) : null;
      }
    }
    return null;
  }

  public List<String> listFiles(String dirPath) throws IOException {
    long dirLbn;
    if (dirPath == null || dirPath.isEmpty() || dirPath.equals("/")) {
      dirLbn = rootIcbLbn;
    } else {
      dirLbn = findDirLbn(dirPath);
      if (dirLbn < 0) {
        return new ArrayList<>();
      }
    }
    return listDirectory(dirLbn);
  }

  private void findFsdAndPartitionBase() throws IOException {
    long totalLength = reader.length();
    long totalSectors = totalLength > 0 ? totalLength / SECTOR_SIZE : -1;
    reader.prefetchRange(AVDP_SECTOR_MAIN * SECTOR_SIZE, (AVDP_SECTOR_RESERVE + 1) * SECTOR_SIZE);
    long mvdsLocation = -1;
    long mvdsLengthBytes = (long) VDS_DEFAULT_SECTORS * SECTOR_SIZE;
    long rvdsLocation = -1;
    long rvdsLengthBytes = 0;
    long[] avdpCandidates = totalSectors > AVDP_SECTOR_MAIN ? new long[]{AVDP_SECTOR_MAIN, AVDP_SECTOR_RESERVE, totalSectors - AVDP_SECTOR_MAIN} : new long[]{AVDP_SECTOR_MAIN, AVDP_SECTOR_RESERVE};
    for (long avdpSector : avdpCandidates) {
      if (!tryReadSector(avdpSector)) {
        continue;
      }
      if (readTag() == TAG_AVDP) {
        mvdsLengthBytes = sectorBB.getInt(AVDP_MVDS_LENGTH_OFFSET) & 0xFFFFFFFFL;
        mvdsLocation = sectorBB.getInt(AVDP_MVDS_LOCATION_OFFSET) & 0xFFFFFFFFL;
        rvdsLengthBytes = sectorBB.getInt(AVDP_RVDS_LENGTH_OFFSET) & 0xFFFFFFFFL;
        rvdsLocation = sectorBB.getInt(AVDP_RVDS_LOCATION_OFFSET) & 0xFFFFFFFFL;
        break;
      }
    }
    if (mvdsLocation < 0) {
      mvdsLocation = VDS_DEFAULT_SECTORS;
    }
    long[] vdsResult = scanVds(mvdsLocation, mvdsLengthBytes);
    if (vdsResult[0] == 0 && rvdsLocation > 0) {
      vdsResult = scanVds(rvdsLocation, rvdsLengthBytes);
    }
    long partitionStart = vdsResult[0];
    long metaFileLoc = vdsResult[1];
    if (partitionStart == 0) {
      throw new IOException("UDF: PartitionDescriptor not found in VDS at sector " + mvdsLocation);
    }
    this.partition0Base = partitionStart;
    if (metaFileLoc >= 0) {
      long metaEfeSector = partition0Base + metaFileLoc;
      if (tryReadSector(metaEfeSector)) {
        int tid = readTag();
        if (tid == TAG_FE || tid == TAG_EFE) {
          long infoLen = sectorBB.getLong(FE_INFO_LENGTH_OFFSET);
          metadataPartitionSectors = infoLen / SECTOR_SIZE;
        }
      }
    }
    fsdPhysicalSector = scanForTag(partitionStart, FSD_SCAN_SECTOR_COUNT);
    if (fsdPhysicalSector == 0) {
      throw new IOException("UDF: FSD not found near partitionStart=" + partitionStart);
    }
    readSector(fsdPhysicalSector);
    rootIcbLbn = sectorBB.getInt(FSD_ROOT_ICB_OFFSET) & 0xFFFFFFFFL;
  }

  private long[] scanVds(long location, long lengthBytes) {
    long sectors = Math.max(1, Util.ceilDivide(lengthBytes, SECTOR_SIZE));
    reader.prefetchRange(location * SECTOR_SIZE, (location + sectors) * SECTOR_SIZE);
    long partitionStart = 0;
    long metaFileLoc = -1;
    for (long s = location; s < location + sectors; s++) {
      if (!tryReadSector(s)) {
        continue;
      }
      int tag = readTag();
      if (tag == TAG_PD) {
        long start = sectorBB.getInt(PD_PARTITION_START_OFFSET) & 0xFFFFFFFFL;
        if ((sectorBB.getShort(PD_ACCESS_TYPE_OFFSET) & 0xFFFF) == 0 || partitionStart == 0) {
          partitionStart = start;
        }
      } else if (tag == TAG_LVD) {
        metaFileLoc = extractMetaFileLoc();
      } else if (tag == TAG_TD) {
        break;
      }
    }
    return new long[]{partitionStart, metaFileLoc};
  }

  private long scanForTag(long start, int count) {
    reader.prefetchRange(start * SECTOR_SIZE, (start + count) * SECTOR_SIZE);
    for (long s = start; s < start + count; s++) {
      if (tryReadSector(s) && readTag() == TAG_FSD) {
        return s;
      }
    }
    return 0;
  }

  private int readTag() {
    return sectorBB.getShort(0) & 0xFFFF;
  }

  private long extractMetaFileLoc() {
    int numMaps = sectorBB.getInt(LVD_NUM_PARTITION_MAPS_OFFSET);
    int pos = LVD_PARTITION_MAPS_START;
    for (int i = 0; i < numMaps; i++) {
      if (pos + 2 > SECTOR_SIZE) {
        break;
      }
      int pmType = sectorBuf[pos] & 0xFF;
      int pmLen = sectorBuf[pos + 1] & 0xFF;
      if (pmLen == 0) {
        break;
      }
      if (pmType == 2 && pos + PM_TYPE2_MIN_SIZE < SECTOR_SIZE) {
        String id8 = new String(sectorBuf, pos + PM_TYPE2_ID_OFFSET, PM_TYPE2_ID_LENGTH, StandardCharsets.ISO_8859_1);
        if (id8.equals("*UDF Met")) {
          return sectorBB.getInt(pos + PM_META_FILOC_OFFSET) & 0xFFFFFFFFL;
        }
      }
      pos += pmLen;
    }
    return -1;
  }

  private long findDirLbn(String dirPath) throws IOException {
    String[] parts = dirPath.replace('\\', '/').split("/");
    long dirLbn = rootIcbLbn;
    for (String part : parts) {
      if (part.isEmpty()) {
        continue;
      }
      Map<String, long[]> entries = readDirectoryEntries(dirLbn);
      String nameLower = part.toLowerCase(Locale.US);
      long[] found = null;
      for (Map.Entry<String, long[]> e : entries.entrySet()) {
        if (e.getKey().toLowerCase(Locale.US).equals(nameLower)) {
          found = e.getValue();
          break;
        }
      }
      if (found == null || found[ENTRY_IS_DIR] == 0) {
        return -1;
      }
      dirLbn = found[ENTRY_OFFSET];
    }
    return dirLbn;
  }

  private List<String> listDirectory(long dirLbn) throws IOException {
    return new ArrayList<>(readDirectoryEntries(dirLbn).keySet());
  }

  private Map<String, long[]> readDirectoryEntries(long dirLbn) throws IOException {
    readSector(fsdPhysicalSector + dirLbn);
    int tagId = readTag();
    if (tagId != TAG_FE && tagId != TAG_EFE) {
      throw new IOException("UDF: expected FE/EFE at lbn=" + dirLbn + ", got tag=" + tagId);
    }
    long[] layout = readFileEntryLayout(tagId);
    int adOffset = (int) layout[0];
    int adLength = (int) layout[1];
    int adType = (int) layout[2];
    long infoLength = layout[3];
    byte[] feSector = sectorBuf.clone();
    Map<String, long[]> result = new HashMap<>();
    if (adType == AD_EMBEDDED) {
      parseDirectoryData(feSector, adOffset, adLength, result);
    } else {
      byte[] dirData = readAllocDesc(feSector, adOffset, adLength, adType, (int) infoLength);
      parseDirectoryData(dirData, 0, dirData.length, result);
    }
    return result;
  }

  private byte[] readAllocDesc(byte[] feSector, int adOffset, int adLength, int adType, int totalLength) throws IOException {
    byte[] data = new byte[totalLength];
    int dataPos = 0;
    int pos = adOffset;
    int end = adOffset + adLength;
    ByteBuffer feBB = ByteBuffer.wrap(feSector).order(ByteOrder.LITTLE_ENDIAN);
    while (pos < end && dataPos < totalLength) {
      long[] ad = readExtentAd(feBB, pos, adType, false);
      long lbn = ad[0];
      long base = ad[1];
      int extentLength = (int) ad[2];
      pos = (int) ad[3];
      if (extentLength == 0) {
        continue;
      }
      int numSectors = Util.ceilDivide(extentLength, SECTOR_SIZE);
      for (int s = 0; s < numSectors && dataPos < totalLength; s++) {
        readSector(base + lbn + s);
        int toCopy = Math.min(SECTOR_SIZE, totalLength - dataPos);
        System.arraycopy(sectorBuf, 0, data, dataPos, toCopy);
        dataPos += toCopy;
      }
    }
    return data;
  }

  private void parseDirectoryData(byte[] data, int offset, int length, Map<String, long[]> out) {
    ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
    int pos = offset;
    int end = offset + length;
    while (pos + FID_MIN_SIZE <= end) {
      int tagId = bb.getShort(pos) & 0xFFFF;
      if (tagId != TAG_FID) {
        break;
      }
      int fileChars = data[pos + FID_FILE_CHARS_OFFSET] & 0xFF;
      int fiLength = data[pos + FID_FI_LENGTH_OFFSET] & 0xFF;
      long icbLbn = bb.getInt(pos + FID_ICB_LBN_OFFSET) & 0xFFFFFFFFL;
      int implUseLength = bb.getShort(pos + FID_IMPL_USE_LEN_OFFSET) & 0xFFFF;
      int nameOffset = pos + FID_MIN_SIZE + implUseLength;
      boolean isDir = (fileChars & 0x02) != 0;
      boolean isParent = (fileChars & 0x08) != 0;
      if (!isParent && fiLength > 0) {
        String name = decodeOsta(data, nameOffset, fiLength);
        if (isDir) {
          out.put(name, new long[]{icbLbn, 0, 1});
        } else {
          try {
            long[] fileInfo = resolveFileEntry(icbLbn);
            out.put(name, new long[]{fileInfo[ENTRY_OFFSET], fileInfo[ENTRY_SIZE], 0});
          } catch (IOException ignored) {
          }
        }
      }
      int fidSize = FID_MIN_SIZE + implUseLength + fiLength;
      int padding = (4 - (fidSize % 4)) % 4;
      pos += fidSize + padding;
    }
  }

  private long[] resolveFileEntry(long icbLbn) throws IOException {
    readSector(fsdPhysicalSector + icbLbn);
    int tagId = readTag();
    if (tagId != TAG_FE && tagId != TAG_EFE) {
      throw new IOException("UDF: expected FE/EFE at icbLbn=" + icbLbn + ", got tag=" + tagId);
    }
    long[] layout = readFileEntryLayout(tagId);
    int adOffset = (int) layout[0];
    int adLength = (int) layout[1];
    int adType = (int) layout[2];
    long infoLength = layout[3];
    if (adType == AD_EMBEDDED || adLength == 0) {
      return new long[]{-1, infoLength};
    }
    byte[] feSector = sectorBuf.clone();
    ByteBuffer feBB = ByteBuffer.wrap(feSector).order(ByteOrder.LITTLE_ENDIAN);
    int pos = adOffset;
    int end = adOffset + adLength;
    long firstByteOffset = -1;
    long nextExpectedSector = -1;
    while (pos < end) {
      long[] ad = readExtentAd(feBB, pos, adType, true);
      long lbn = ad[0];
      long base = ad[1];
      int extentLength = (int) ad[2];
      pos = (int) ad[3];
      if (extentLength == 0) {
        continue;
      }
      long physicalSector = base + lbn;
      if (firstByteOffset < 0) {
        firstByteOffset = physicalSector * SECTOR_SIZE;
      } else if (physicalSector != nextExpectedSector) {
        break;
      }
      nextExpectedSector = physicalSector + Util.ceilDivide(extentLength, SECTOR_SIZE);
    }
    return new long[]{firstByteOffset < 0 ? -1 : firstByteOffset, infoLength};
  }

  private long[] readFileEntryLayout(int tagId) {
    long infoLength = sectorBB.getLong(FE_INFO_LENGTH_OFFSET);
    int icbTagFlags = sectorBB.getShort(FE_ICB_TAG_FLAGS_OFFSET) & 0xFFFF;
    int adType = icbTagFlags & 0x7;
    int eaLength, adLength, adOffset;
    if (tagId == TAG_FE) {
      eaLength = sectorBB.getInt(FE_EA_LENGTH_OFFSET);
      adLength = sectorBB.getInt(FE_AD_LENGTH_OFFSET);
      adOffset = FE_AD_START_OFFSET + eaLength;
    } else {
      eaLength = sectorBB.getInt(EFE_EA_LENGTH_OFFSET);
      adLength = sectorBB.getInt(EFE_AD_LENGTH_OFFSET);
      adOffset = EFE_AD_START_OFFSET + eaLength;
    }
    return new long[]{adOffset, adLength, adType, infoLength};
  }

  private long[] readExtentAd(ByteBuffer bb, int pos, int adType, boolean resolvePartRef) {
    int extentLength = bb.getInt(pos) & 0x3FFFFFFF;
    long lbn;
    long base = fsdPhysicalSector;
    int nextPos;
    switch (adType) {
      case AD_LONG:
        lbn = bb.getInt(pos + 4) & 0xFFFFFFFFL;
        if (resolvePartRef) {
          int partRef = bb.getShort(pos + 8) & 0xFFFF;
          base = (partRef == 0) ? partition0Base : fsdPhysicalSector;
        }
        nextPos = pos + AD_LONG_SIZE;
        break;
      case AD_EXTENDED:
        lbn = bb.getInt(pos + 12) & 0xFFFFFFFFL;
        if (resolvePartRef) {
          int partRef = bb.getShort(pos + 16) & 0xFFFF;
          base = (partRef == 0) ? partition0Base : fsdPhysicalSector;
        }
        nextPos = pos + AD_EXTENDED_SIZE;
        break;
      default:
        lbn = bb.getInt(pos + 4) & 0xFFFFFFFFL;
        if (metadataPartitionSectors > 0 && lbn < metadataPartitionSectors) {
          base = fsdPhysicalSector;
        } else {
          base = partition0Base;
        }
        nextPos = pos + AD_SHORT_SIZE;
        break;
    }
    return new long[]{lbn, base, extentLength, nextPos};
  }

  private String decodeOsta(byte[] data, int offset, int length) {
    if (length == 0) {
      return "";
    }
    int compressionId = data[offset] & 0xFF;
    if (compressionId == OSTA_CS0_UTF16BE) {
      try {
        return new String(data, offset + 1, length - 1, StandardCharsets.UTF_16BE);
      } catch (Exception e) {
        return "";
      }
    }
    return new String(data, offset + 1, length - 1, StandardCharsets.ISO_8859_1);
  }

  private void readSector(long sectorNumber) throws IOException {
    long byteOffset = sectorNumber * SECTOR_SIZE;
    int total = 0;
    while (total < SECTOR_SIZE) {
      int n = reader.read(byteOffset + total, sectorBuf, total, SECTOR_SIZE - total);
      if (n == -1) {
        break;
      }
      total += n;
    }
    if (total < SECTOR_SIZE) {
      Arrays.fill(sectorBuf, total, SECTOR_SIZE, (byte) 0);
    }
    sectorBB.rewind();
  }

  private boolean tryReadSector(long sectorNumber) {
    try {
      readSector(sectorNumber);
      return true;
    } catch (IOException e) {
      return false;
    }
  }
}