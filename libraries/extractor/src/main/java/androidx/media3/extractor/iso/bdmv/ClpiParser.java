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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class ClpiParser {

  private static final int CLPI_HEADER_MIN_SIZE = 20;
  private static final int CLPI_SEQUENCE_INFO_ADDR_OFFSET = 8;
  private static final int CLPI_PROGRAM_INFO_ADDR_OFFSET = 12;
  private static final int CLPI_CPI_ADDR_OFFSET = 16;
  private static final int CPI_TYPE_EP_MAP = 1;

  private static final int STC_SEQ_ENTRY_SIZE = 14;
  private static final int ATC_SEQ_HEADER_SIZE = 6;

  private static final int EP_MAP_PID_ENTRY_SIZE = 12;
  private static final int EP_COARSE_ENTRY_SIZE = 8;
  private static final int EP_FINE_ENTRY_SIZE = 4;

  private static final int PROGRAM_INFO_NR_PROGRAMS_OFFSET = 5;
  private static final int PROGRAM_INFO_HEADER_SIZE = 6;
  private static final int PROGRAM_ENTRY_NR_STREAMS_OFFSET = 6;
  private static final int PROGRAM_ENTRY_HEADER_SIZE = 8;

  public static ClpiInfo parse(byte[] data, String clipName) throws IOException {
    if (data.length < CLPI_HEADER_MIN_SIZE) {
      throw new IOException("CLPI: file too short");
    }
    ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
    String magic = new String(data, 0, 4, StandardCharsets.US_ASCII);
    if (!magic.equals("HDMV")) {
      throw new IOException("CLPI: bad magic: " + magic);
    }
    int sequenceInfoAddr = bb.getInt(CLPI_SEQUENCE_INFO_ADDR_OFFSET);
    int programInfoAddr = bb.getInt(CLPI_PROGRAM_INFO_ADDR_OFFSET);
    int cpiAddr = bb.getInt(CLPI_CPI_ADDR_OFFSET);
    long durationTicks = 0;
    int pcrPid = 0;
    long stcStartTime = 0;
    List<Long> spnStcStartsList = new ArrayList<>();
    List<Long> stcPresentationStartTimesList = new ArrayList<>();
    List<Long> stcPresentationEndTimesList = new ArrayList<>();
    if (sequenceInfoAddr + ATC_SEQ_HEADER_SIZE <= data.length) {
      int numAtcSeq = data[sequenceInfoAddr + 5] & 0xFF;
      int atcPos = sequenceInfoAddr + ATC_SEQ_HEADER_SIZE;
      for (int a = 0; a < numAtcSeq && atcPos + ATC_SEQ_HEADER_SIZE <= data.length; a++) {
        int numStcSeq = data[atcPos + 4] & 0xFF;
        int stcPos = atcPos + ATC_SEQ_HEADER_SIZE;
        for (int s = 0; s < numStcSeq && stcPos + STC_SEQ_ENTRY_SIZE <= data.length; s++) {
          int thisPcrPid = bb.getShort(stcPos) & 0xFFFF;
          long spnStcStart = bb.getInt(stcPos + 2) & 0xFFFFFFFFL;
          long startTime = bb.getInt(stcPos + 6) & 0xFFFFFFFFL;
          long endTime = bb.getInt(stcPos + 10) & 0xFFFFFFFFL;
          long dur = endTime - startTime;
          spnStcStartsList.add(spnStcStart);
          stcPresentationStartTimesList.add(startTime);
          stcPresentationEndTimesList.add(endTime);
          if (dur > 0) {
            durationTicks += dur;
          }
          if (s == 0 && a == 0) {
            pcrPid = thisPcrPid;
            stcStartTime = startTime;
          }
          stcPos += STC_SEQ_ENTRY_SIZE;
        }
        atcPos += ATC_SEQ_HEADER_SIZE + numStcSeq * STC_SEQ_ENTRY_SIZE;
      }
    }
    long[] spnStcStarts = toLongArray(spnStcStartsList);
    long[] stcPresentationStartTimes = toLongArray(stcPresentationStartTimesList);
    long[] stcPresentationEndTimes = toLongArray(stcPresentationEndTimesList);
    List<StreamInfo> streams = new ArrayList<>();
    if (programInfoAddr + PROGRAM_INFO_HEADER_SIZE <= data.length) {
      int numPrograms = data[programInfoAddr + PROGRAM_INFO_NR_PROGRAMS_OFFSET] & 0xFF;
      int progPos = programInfoAddr + PROGRAM_INFO_HEADER_SIZE;
      for (int p = 0; p < numPrograms && progPos + PROGRAM_ENTRY_HEADER_SIZE <= data.length; p++) {
        int numStreams = data[progPos + PROGRAM_ENTRY_NR_STREAMS_OFFSET] & 0xFF;
        int streamPos = progPos + PROGRAM_ENTRY_HEADER_SIZE;
        for (int s = 0; s < numStreams && streamPos + 3 <= data.length; s++) {
          int pid = bb.getShort(streamPos) & 0xFFFF;
          int attrPos = streamPos + 2;
          if (attrPos + 2 > data.length) {
            break;
          }
          int attrLength = data[attrPos] & 0xFF;
          int streamType = data[attrPos + 1] & 0xFF;
          String lang = "";
          boolean isAudio = BdmvConstants.isAudioStreamType(streamType);
          boolean isSubtitle90 = BdmvConstants.isSubtitle90StreamType(streamType);
          boolean isSubtitle92 = BdmvConstants.isSubtitle92StreamType(streamType);
          if (isAudio && attrLength >= 5 && attrPos + 6 <= data.length) {
            lang = BdmvConstants.parseLang(data, attrPos + 3);
          } else if (isSubtitle90 && attrLength >= 4 && attrPos + 5 <= data.length) {
            lang = BdmvConstants.parseLang(data, attrPos + 2);
          } else if (isSubtitle92 && attrLength >= 5 && attrPos + 6 <= data.length) {
            lang = BdmvConstants.parseLang(data, attrPos + 3);
          }
          int dynamicRangeType = 0, colorSpace = 0, hdrPlusFlag = 0;
          if (streamType == BdmvConstants.STREAM_TYPE_HEVC && attrLength >= 5 && attrPos + 6 <= data.length) {
            dynamicRangeType = (data[attrPos + 4] >> 4) & 0xF;
            colorSpace = data[attrPos + 4] & 0xF;
            hdrPlusFlag = (data[attrPos + 5] >> 7) & 1;
          }
          streams.add(new StreamInfo(pid, streamType, lang, dynamicRangeType, colorSpace, hdrPlusFlag));
          streamPos = attrPos + 1 + attrLength;
        }
        progPos = streamPos;
      }
    }
    List<EpMapEntry> epMap = new ArrayList<>();
    if (cpiAddr + 10 <= data.length) {
      int cpiType = bb.getShort(cpiAddr + 4) & 0xF;
      if (cpiType == CPI_TYPE_EP_MAP) {
        int epMapPos = cpiAddr + 6;
        int numPidEntries = data[cpiAddr + 7] & 0xFF;
        int pidEntryPos = cpiAddr + 8;
        for (int e = 0; e < numPidEntries && pidEntryPos + EP_MAP_PID_ENTRY_SIZE <= data.length; e++) {
          int streamPid = bb.getShort(pidEntryPos) & 0xFFFF;
          long word3 = bb.getInt(pidEntryPos + 3) & 0xFFFFFFFFL;
          int numEpCoarse = (int) ((word3 >> 10) & 0xFFFF);
          long word5 = bb.getInt(pidEntryPos + 5) & 0xFFFFFFFFL;
          int numEpFine = (int) ((word5 >> 8) & 0x3FFFF);
          long startAddrRel = bb.getInt(pidEntryPos + 8) & 0xFFFFFFFFL;
          long streamStartAddr = startAddrRel + epMapPos;
          if (streamPid == pcrPid || e == 0) {
            parseEpEntries(data, (int) streamStartAddr, numEpCoarse, numEpFine, epMap);
          }
          pidEntryPos += EP_MAP_PID_ENTRY_SIZE;
        }
      }
    }
    return new ClpiInfo(clipName, streams, epMap, durationTicks, pcrPid, stcStartTime, spnStcStarts, stcPresentationStartTimes, stcPresentationEndTimes);
  }

  private static long[] toLongArray(List<Long> list) {
    long[] arr = new long[list.size()];
    for (int i = 0; i < arr.length; i++) {
      arr[i] = list.get(i);
    }
    return arr;
  }

  private static void parseEpEntries(byte[] data, int startAddr, int numCoarse, int numFine, List<EpMapEntry> out) {
    if (startAddr < 0 || startAddr + EP_FINE_ENTRY_SIZE > data.length) {
      return;
    }
    ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
    long fineStartRel = bb.getInt(startAddr) & 0xFFFFFFFFL;
    int coarseBase = startAddr + 4;
    int fineBase = (int) (startAddr + fineStartRel);
    if (coarseBase + numCoarse * EP_COARSE_ENTRY_SIZE > data.length) {
      return;
    }
    if (fineBase < 0 || fineBase >= data.length) {
      return;
    }
    if (fineBase + numFine * EP_FINE_ENTRY_SIZE > data.length) {
      numFine = (data.length - fineBase) / EP_FINE_ENTRY_SIZE;
    }
    for (int c = 0; c < numCoarse; c++) {
      int cOffset = coarseBase + c * EP_COARSE_ENTRY_SIZE;
      long coarseWord0 = bb.getInt(cOffset) & 0xFFFFFFFFL;
      long coarsePts = coarseWord0 & 0x3FFFL;
      long refFineId = (coarseWord0 >> 14) & 0x3FFFFL;
      long coarseSpn = bb.getInt(cOffset + 4) & 0xFFFFFFFFL;
      int nextRefFineId = (c + 1 < numCoarse) ? (int) ((bb.getInt(coarseBase + (c + 1) * EP_COARSE_ENTRY_SIZE) >> 14) & 0x3FFFFL) : numFine;
      for (int f = (int) refFineId; f < nextRefFineId && f < numFine; f++) {
        int fOffset = fineBase + f * EP_FINE_ENTRY_SIZE;
        long fineWord = bb.getInt(fOffset) & 0xFFFFFFFFL;
        boolean isAngleChangePoint = (fineWord & 0x80000000L) != 0;
        long finePts = (fineWord >> 17) & 0x7FFL;
        long fineSpn = fineWord & 0x1FFFFL;
        long fullPts = ((coarsePts & ~1L) << 18) | (finePts << 8);
        long fullSpn = (coarseSpn & ~0x1FFFFL) | fineSpn;
        long byteOffset = fullSpn * BdmvConstants.M2TS_PACKET_SIZE;
        out.add(new EpMapEntry(fullPts, byteOffset, isAngleChangePoint));
      }
    }
  }
}
