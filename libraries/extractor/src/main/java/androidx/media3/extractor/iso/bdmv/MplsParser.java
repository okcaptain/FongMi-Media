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
import java.util.Collections;
import java.util.List;

public final class MplsParser {

  private static final int MPLS_HEADER_MIN_SIZE = 16;
  private static final int MPLS_LIST_ADDR_OFFSET = 8;
  private static final int MPLS_MARK_ADDR_OFFSET = 12;
  private static final int MPLS_PLAY_ITEM_LIST_OFFSET = 10;
  private static final int MPLS_MARK_ENTRY_SIZE = 14;

  private static final int PLAY_ITEM_STN_OFFSET = 34;
  private static final int MULTI_ANGLE_ENTRY_SIZE = 10;
  private static final int STN_STREAM_ARRAY_OFFSET = 16;

  private static final int PLAY_LIST_NUM_ITEMS_OFFSET = 6;

  private static final int MARK_NUM_MARKS_OFFSET = 4;
  private static final int MARK_LIST_HEADER_SIZE = 6;
  private static final int MARK_TYPE_OFFSET = 1;
  private static final int MARK_PLAY_ITEM_OFFSET = 2;
  private static final int MARK_TIMESTAMP_OFFSET = 4;

  private static final int STN_NUM_VIDEO_OFFSET = 4;
  private static final int STN_NUM_AUDIO_OFFSET = 5;
  private static final int STN_NUM_PG_OFFSET = 6;
  private static final int STN_NUM_IG_OFFSET = 7;
  private static final int STN_NUM_SEC_AUDIO_OFFSET = 8;
  private static final int STN_NUM_SEC_VIDEO_OFFSET = 9;
  private static final int STN_NUM_PIP_PG_OFFSET = 10;

  public static Playlist parse(byte[] data, String name) throws IOException {
    ByteBuffer bb = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
    if (data.length < MPLS_HEADER_MIN_SIZE) {
      throw new IOException("MPLS: file too short");
    }
    String magic = new String(data, 0, 4, StandardCharsets.US_ASCII);
    if (!magic.equals("MPLS")) {
      throw new IOException("MPLS: bad magic: " + magic);
    }
    int listPos = bb.getInt(MPLS_LIST_ADDR_OFFSET);
    int markPos = bb.getInt(MPLS_MARK_ADDR_OFFSET);
    if (listPos + MPLS_PLAY_ITEM_LIST_OFFSET > data.length) {
      throw new IOException("MPLS: list_pos out of range");
    }
    int numPlayItems = bb.getShort(listPos + PLAY_LIST_NUM_ITEMS_OFFSET) & 0xFFFF;
    List<PlayItem> playItems = new ArrayList<>();
    int pos = listPos + MPLS_PLAY_ITEM_LIST_OFFSET;
    for (int i = 0; i < numPlayItems; i++) {
      if (pos + 2 > data.length) {
        break;
      }
      int itemLength = bb.getShort(pos) & 0xFFFF;
      int itemEnd = pos + 2 + itemLength;
      if (itemEnd > data.length) {
        break;
      }
      String clipId = new String(data, pos + 2, 5, StandardCharsets.US_ASCII).trim();
      if (pos + 22 > data.length) {
        break;
      }
      int connectionCondition = data[pos + 12] & 0x0F;
      boolean isMultiAngle = (data[pos + 12] & 0x10) != 0;
      long inTime = bb.getInt(pos + 14) & 0xFFFFFFFFL;
      long outTime = bb.getInt(pos + 18) & 0xFFFFFFFFL;
      List<MplsStreamEntry> stnStreams = parseStn(data, bb, pos, itemEnd, isMultiAngle);
      playItems.add(new PlayItem(clipId, inTime, outTime, connectionCondition, stnStreams));
      pos = itemEnd;
    }
    List<ChapterMark> marks = new ArrayList<>();
    if (markPos + MARK_NUM_MARKS_OFFSET <= data.length) {
      int numMarks = bb.getShort(markPos + MARK_NUM_MARKS_OFFSET) & 0xFFFF;
      int markOffset = markPos + MARK_LIST_HEADER_SIZE;
      for (int m = 0; m < numMarks && markOffset + MPLS_MARK_ENTRY_SIZE <= data.length; m++) {
        int markType = data[markOffset + MARK_TYPE_OFFSET] & 0xFF;
        int playItemRef = bb.getShort(markOffset + MARK_PLAY_ITEM_OFFSET) & 0xFFFF;
        long markTimestamp = bb.getInt(markOffset + MARK_TIMESTAMP_OFFSET) & 0xFFFFFFFFL;
        marks.add(new ChapterMark(markType, playItemRef, markTimestamp));
        markOffset += MPLS_MARK_ENTRY_SIZE;
      }
    }
    return new Playlist(name, playItems, marks);
  }

  private static int skipSecondaryExtraAttrs(byte[] data, int sp, int stnEnd) {
    if (sp >= stnEnd) {
      return sp;
    }
    int numRef = data[sp] & 0xFF;
    sp += 2;
    sp += numRef;
    if (numRef % 2 != 0) {
      sp++;
    }
    return Math.min(sp, stnEnd);
  }

  private static List<MplsStreamEntry> parseStn(byte[] data, ByteBuffer bb, int piBase, int piEnd, boolean isMultiAngle) {
    int stnBase = piBase + PLAY_ITEM_STN_OFFSET;
    if (isMultiAngle && stnBase + 2 <= piEnd) {
      int numAngles = data[stnBase] & 0xFF;
      stnBase += 2 + (numAngles - 1) * MULTI_ANGLE_ENTRY_SIZE;
    }
    if (stnBase + STN_STREAM_ARRAY_OFFSET > piEnd) {
      return Collections.emptyList();
    }
    int stnLength = bb.getShort(stnBase) & 0xFFFF;
    int stnEnd = Math.min(piEnd, stnBase + 2 + stnLength);
    int numVideo = data[stnBase + STN_NUM_VIDEO_OFFSET] & 0xFF;
    int numAudio = data[stnBase + STN_NUM_AUDIO_OFFSET] & 0xFF;
    int numPg = data[stnBase + STN_NUM_PG_OFFSET] & 0xFF;
    int numIg = data[stnBase + STN_NUM_IG_OFFSET] & 0xFF;
    int numSecAudio = data[stnBase + STN_NUM_SEC_AUDIO_OFFSET] & 0xFF;
    int numSecVideo = data[stnBase + STN_NUM_SEC_VIDEO_OFFSET] & 0xFF;
    int numPipPg = data[stnBase + STN_NUM_PIP_PG_OFFSET] & 0xFF;
    int[] counts = {numVideo, numAudio, numPg + numPipPg, numIg, numSecAudio, numSecVideo};
    List<MplsStreamEntry> result = new ArrayList<>();
    int sp = stnBase + STN_STREAM_ARRAY_OFFSET;
    for (int g = 0; g < counts.length; g++) {
      int streamGroup = g + 1;
      for (int s = 0; s < counts[g]; s++) {
        if (sp + 2 > stnEnd) {
          return result;
        }
        int entryLen = data[sp] & 0xFF;
        if (sp + 1 + entryLen > stnEnd) {
          return result;
        }
        int entryStreamType = data[sp + 1] & 0xFF;
        int pid = 0;
        if (entryStreamType == 1 && entryLen >= 3) {
          pid = bb.getShort(sp + 2) & 0xFFFF;
        } else if (entryStreamType == 2 && entryLen >= 5) {
          pid = bb.getShort(sp + 4) & 0xFFFF;
        } else if ((entryStreamType == 3 || entryStreamType == 4) && entryLen >= 4) {
          pid = bb.getShort(sp + 3) & 0xFFFF;
        }
        sp += 1 + entryLen;
        if (sp >= stnEnd) {
          break;
        }
        int attrLen = data[sp] & 0xFF;
        int attrEnd = sp + 1 + attrLen;
        if (attrEnd > stnEnd) {
          sp = attrEnd;
          break;
        }
        int codingType = (attrLen >= 1) ? (data[sp + 1] & 0xFF) : 0;
        String lang = "";
        boolean isAudio = BdmvConstants.isAudioStreamType(codingType);
        boolean isSub90 = BdmvConstants.isSubtitle90StreamType(codingType);
        boolean isSub92 = BdmvConstants.isSubtitle92StreamType(codingType);
        if (isAudio && attrLen >= 4 && sp + 5 <= stnEnd) {
          lang = BdmvConstants.parseLang(data, sp + 3);
        } else if (isSub90 && attrLen >= 3 && sp + 4 <= stnEnd) {
          lang = BdmvConstants.parseLang(data, sp + 2);
        } else if (isSub92 && attrLen >= 4 && sp + 5 <= stnEnd) {
          lang = BdmvConstants.parseLang(data, sp + 3);
        }
        result.add(new MplsStreamEntry(streamGroup, pid, codingType, lang));
        sp = attrEnd;
        if (streamGroup == BdmvConstants.STREAM_GROUP_SEC_AUDIO || streamGroup == BdmvConstants.STREAM_GROUP_SEC_VIDEO) {
          sp = skipSecondaryExtraAttrs(data, sp, stnEnd);
          if (streamGroup == BdmvConstants.STREAM_GROUP_SEC_VIDEO) {
            sp = skipSecondaryExtraAttrs(data, sp, stnEnd);
          }
        }
      }
    }
    return result;
  }
}
