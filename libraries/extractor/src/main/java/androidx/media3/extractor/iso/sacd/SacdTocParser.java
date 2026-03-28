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
import androidx.media3.common.C;
import androidx.media3.common.CacheDataReader;
import androidx.media3.common.util.Util;
import androidx.media3.extractor.iso.IsoConstants;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class SacdTocParser {

  private static final int MASTER_TOC_SECTOR = 510;
  private static final byte[] MAGIC_SACDMTOC = {'S', 'A', 'C', 'D', 'M', 'T', 'O', 'C'};

  private static final int AREA_TOC_SECTOR = 544;
  private static final byte[] MAGIC_TWOCHTOC = {'T', 'W', 'O', 'C', 'H', 'T', 'O', 'C'};
  private static final byte[] MAGIC_MULCHTOC = {'M', 'U', 'L', 'C', 'H', 'T', 'O', 'C'};
  private static final byte[] MAGIC_SACDTRL1 = {'S', 'A', 'C', 'D', 'T', 'R', 'L', '1'};
  private static final byte[] MAGIC_SACDTRL2 = {'S', 'A', 'C', 'D', 'T', 'R', 'L', '2'};

  private static final int AREA_TOC_NUM_TRACKS_OFFSET = 0x45;
  private static final int AREA_TOC_FRAME_FORMAT_OFFSET = 0x15;
  private static final int AREA_TOC_CHANNEL_COUNT_OFFSET = 0x20;
  private static final int AREA_TOC_AUDIO_START_OFFSET = 0x48;
  private static final int AREA_TOC_AUDIO_END_OFFSET = 0x4C;

  private static final int MAGIC_SIZE = 8;
  private static final int TRL1_RELATIVE_OFFSET = 1;
  private static final int TRL2_RELATIVE_OFFSET = 2;

  private static final int TRL1_START_LSN_ARRAY_OFFSET = MAGIC_SIZE;
  private static final int TRL1_LENGTH_LSN_ARRAY_OFFSET = MAGIC_SIZE + 255 * 4;

  private static final int TRL2_DURATION_ARRAY_OFFSET = MAGIC_SIZE + 255 * 4;

  public static boolean isSacd(CacheDataReader reader) throws IOException {
    byte[] magic = new byte[MAGIC_SIZE];
    int read = reader.read((long) MASTER_TOC_SECTOR * IsoConstants.SECTOR_SIZE, magic, 0, MAGIC_SIZE);
    return read == MAGIC_SIZE && Arrays.equals(magic, MAGIC_SACDMTOC);
  }

  public static SacdStructure parse(CacheDataReader reader) throws IOException {
    byte[] areaSector = readSector(reader, AREA_TOC_SECTOR);
    ByteBuffer areaBb = ByteBuffer.wrap(areaSector).order(ByteOrder.BIG_ENDIAN);
    byte[] areaMagic = Arrays.copyOf(areaSector, MAGIC_SIZE);
    boolean isStereo = Arrays.equals(areaMagic, MAGIC_TWOCHTOC);
    boolean isMulti = Arrays.equals(areaMagic, MAGIC_MULCHTOC);
    if (!isStereo && !isMulti) {
      throw new IOException("SACD: unrecognised area TOC magic at sector " + AREA_TOC_SECTOR + " (got: " + Util.toHexString(areaMagic) + ")");
    }
    SacdArea firstArea = parseArea(reader, areaBb, areaSector, AREA_TOC_SECTOR, isStereo ? SacdArea.TYPE_STEREO : SacdArea.TYPE_MULTI);
    SacdArea secondArea = tryParseSecondArea(reader, firstArea);
    SacdArea stereoArea;
    SacdArea multiArea;
    if (firstArea.type == SacdArea.TYPE_STEREO) {
      stereoArea = firstArea;
      multiArea = secondArea;
    } else {
      multiArea = firstArea;
      stereoArea = secondArea;
    }
    return new SacdStructure(stereoArea, multiArea);
  }

  private static SacdArea parseArea(CacheDataReader reader, ByteBuffer areaBb, byte[] areaSector, long areaTocSector, int type) throws IOException {
    int numTracks = areaSector[AREA_TOC_NUM_TRACKS_OFFSET] & 0xFF;
    int frameFormat = areaSector[AREA_TOC_FRAME_FORMAT_OFFSET] & 0x0F;
    int audioEncoding = (frameFormat == 0) ? SacdArea.ENCODING_DST : SacdArea.ENCODING_DSD;
    int channelCount = areaSector[AREA_TOC_CHANNEL_COUNT_OFFSET] & 0xFF;
    if (channelCount == 0) {
      channelCount = (type == SacdArea.TYPE_STEREO) ? 2 : 6;
    }
    long audioStartSector = areaBb.getInt(AREA_TOC_AUDIO_START_OFFSET) & 0xFFFFFFFFL;
    long audioEndSector = areaBb.getInt(AREA_TOC_AUDIO_END_OFFSET) & 0xFFFFFFFFL;
    long trl1Sector = areaTocSector + TRL1_RELATIVE_OFFSET;
    long trl2Sector = areaTocSector + TRL2_RELATIVE_OFFSET;
    List<SacdTrack> tracks = parseTracks(reader, trl1Sector, trl2Sector, numTracks, channelCount, audioEndSector);
    return new SacdArea(type, audioEncoding, channelCount, audioStartSector, audioEndSector, tracks);
  }

  private static List<SacdTrack> parseTracks(CacheDataReader reader, long trl1Sector, long trl2Sector, int numTracks, int channelCount, long audioEndSector) throws IOException {
    List<SacdTrack> tracks = new ArrayList<>(numTracks);
    if (numTracks == 0) {
      return tracks;
    }
    byte[] trl1Data = readSector(reader, trl1Sector);
    byte[] trl1Magic = Arrays.copyOf(trl1Data, MAGIC_SIZE);
    if (!Arrays.equals(trl1Magic, MAGIC_SACDTRL1)) {
      tracks.add(new SacdTrack(1, channelCount, 0, audioEndSector, 0));
      return tracks;
    }
    ByteBuffer trl1Bb = ByteBuffer.wrap(trl1Data).order(ByteOrder.BIG_ENDIAN);
    byte[] trl2Data = null;
    try {
      byte[] trl2Raw = readSector(reader, trl2Sector);
      if (Arrays.equals(Arrays.copyOf(trl2Raw, MAGIC_SIZE), MAGIC_SACDTRL2)) {
        trl2Data = trl2Raw;
      }
    } catch (IOException ignored) {
    }
    ByteBuffer trl2Bb = (trl2Data != null) ? ByteBuffer.wrap(trl2Data).order(ByteOrder.BIG_ENDIAN) : null;
    for (int t = 0; t < numTracks; t++) {
      int startOff = TRL1_START_LSN_ARRAY_OFFSET + t * 4;
      int lengthOff = TRL1_LENGTH_LSN_ARRAY_OFFSET + t * 4;
      if (lengthOff + 4 > IsoConstants.SECTOR_SIZE) {
        break;
      }
      long startLsn = trl1Bb.getInt(startOff) & 0xFFFFFFFFL;
      long lengthLsn = trl1Bb.getInt(lengthOff) & 0xFFFFFFFFL;
      if (lengthLsn == 0) {
        continue;
      }
      long durationUs = 0;
      if (trl2Bb != null) {
        int durOff = TRL2_DURATION_ARRAY_OFFSET + t * 4;
        if (durOff + 4 <= IsoConstants.SECTOR_SIZE) {
          int durMin = trl2Bb.get(durOff) & 0xFF;
          int durSec = trl2Bb.get(durOff + 1) & 0xFF;
          int durFrame = trl2Bb.get(durOff + 2) & 0xFF;
          durationUs = Util.scaleLargeTimestamp((durMin * 60L + durSec) * 75L + durFrame, C.MICROS_PER_SECOND, 75L);
        }
      }
      if (durationUs <= 0) {
        durationUs = lengthLsn * 2040L * 1_000_000L / (channelCount * 352_800L);
      }
      tracks.add(new SacdTrack(t + 1, channelCount, startLsn, lengthLsn, durationUs));
    }
    if (tracks.isEmpty()) {
      tracks.add(new SacdTrack(1, channelCount, 0, audioEndSector, 0));
    }
    return tracks;
  }

  @Nullable
  private static SacdArea tryParseSecondArea(CacheDataReader reader, SacdArea firstArea) {
    long candidateSector = firstArea.audioEndSector + 1;
    try {
      byte[] candidateData = readSector(reader, candidateSector);
      byte[] magic = Arrays.copyOf(candidateData, MAGIC_SIZE);
      boolean isStereo = Arrays.equals(magic, MAGIC_TWOCHTOC);
      boolean isMulti = Arrays.equals(magic, MAGIC_MULCHTOC);
      if (!isStereo && !isMulti) {
        return null;
      }
      ByteBuffer bb = ByteBuffer.wrap(candidateData).order(ByteOrder.BIG_ENDIAN);
      return parseArea(reader, bb, candidateData, candidateSector, isStereo ? SacdArea.TYPE_STEREO : SacdArea.TYPE_MULTI);
    } catch (IOException ignored) {
      return null;
    }
  }

  private static byte[] readSector(CacheDataReader reader, long sector) throws IOException {
    byte[] buf = new byte[IsoConstants.SECTOR_SIZE];
    int read = reader.read(sector * IsoConstants.SECTOR_SIZE, buf, 0, IsoConstants.SECTOR_SIZE);
    if (read < IsoConstants.SECTOR_SIZE) {
      throw new IOException("SACD: short read at sector " + sector + ": " + read + " bytes");
    }
    return buf;
  }
}
