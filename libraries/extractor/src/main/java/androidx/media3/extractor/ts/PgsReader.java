package androidx.media3.extractor.ts;

import static androidx.media3.extractor.ts.TsPayloadReader.FLAG_DATA_ALIGNMENT_INDICATOR;
import static com.google.common.base.Preconditions.checkState;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.ParsableByteArray;
import androidx.media3.extractor.ExtractorOutput;
import androidx.media3.extractor.TrackOutput;
import androidx.media3.extractor.ts.TsPayloadReader.TrackIdGenerator;

import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

public final class PgsReader implements ElementaryStreamReader {

  private static final int SECTION_TYPE_PALETTE = 0x14;
  private static final int SECTION_TYPE_BITMAP_PICTURE = 0x15;
  private static final int SECTION_TYPE_IDENTIFIER = 0x16;
  private static final int SECTION_TYPE_WINDOW_DEF = 0x17;
  private static final int SECTION_TYPE_END = 0x80;

  @Nullable
  private final String language;
  private @MonotonicNonNull TrackOutput output;

  private static final int STATE_EXPECT_NEXT = -1;
  private static final int STATE_SECTION_TYPE_READ = 0;
  private static final int STATE_SECTION_SIZE_FIRST_BYTE_READ = 1;
  private static final int STATE_SECTION_BYTES_COUNTDOWN = 2;

  private int stateOfReading;
  private int sectionType;
  private int sectionBytesToRead;
  private int firstByteOfSectionSize;
  private int sampleBytesWritten;
  private long sampleTimeUs;
  private boolean packageGoodToGo;

  public PgsReader(@Nullable String language) {
    stateOfReading = STATE_EXPECT_NEXT;
    sectionType = -1;
    sectionBytesToRead = 0;
    this.language = language;
    sampleBytesWritten = 0;
    sampleTimeUs = C.TIME_UNSET;
  }

  @Override
  public void seek() {
    packageGoodToGo = false;
    sampleTimeUs = C.TIME_UNSET;
    stateOfReading = STATE_EXPECT_NEXT;
    sectionType = -1;
    sectionBytesToRead = 0;
    sampleBytesWritten = 0;
  }

  @Override
  public void createTracks(ExtractorOutput extractorOutput, TrackIdGenerator idGenerator) {
    idGenerator.generateNewId();
    output = extractorOutput.track(idGenerator.getTrackId(), C.TRACK_TYPE_TEXT);
    output.format(
        new Format.Builder()
            .setId(idGenerator.getFormatId())
            .setSampleMimeType(MimeTypes.APPLICATION_PGS)
            .setLanguage(language)
            .setCueReplacementBehavior(Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE)
            .build());
  }

  @Override
  public void packetStarted(long pesTimeUs, @TsPayloadReader.Flags int flags) {
    if ((flags & FLAG_DATA_ALIGNMENT_INDICATOR) == 0) {
      return;
    }
    packageGoodToGo = true;
    if (sampleTimeUs == C.TIME_UNSET) {
      sampleTimeUs = pesTimeUs;
    }
  }

  @Override
  public void packetFinished(boolean isEndOfInput) {
    if (!packageGoodToGo) {
      return;
    }
    checkState(sampleTimeUs != C.TIME_UNSET);
    if (stateOfReading == STATE_EXPECT_NEXT && sectionType == SECTION_TYPE_END) {
      output.sampleMetadata(sampleTimeUs, C.BUFFER_FLAG_KEY_FRAME, sampleBytesWritten, 0, null);
      sampleBytesWritten = 0;
      sampleTimeUs = C.TIME_UNSET;
    }
    packageGoodToGo = false;
  }

  @Override
  public void consume(ParsableByteArray data) {
    if (!packageGoodToGo) {
      return;
    }
    int dataPosition = data.getPosition();
    goThrough(data);
    data.setPosition(dataPosition);
    int bytesAvailable = data.bytesLeft();
    output.sampleData(data, bytesAvailable);
    sampleBytesWritten += bytesAvailable;
  }

  private void goThrough(ParsableByteArray array) {
    byte[] buffer = array.getData();
    int position = array.getPosition();
    int limit = array.limit();
    while (limit - position > 0) {
      int b = buffer[position++] & 0xff;
      switch (stateOfReading) {
        case STATE_EXPECT_NEXT:
          if (b == SECTION_TYPE_IDENTIFIER || b == SECTION_TYPE_WINDOW_DEF || b == SECTION_TYPE_PALETTE || b == SECTION_TYPE_BITMAP_PICTURE || b == SECTION_TYPE_END) {
            sectionType = b;
            stateOfReading = STATE_SECTION_TYPE_READ;
          }
          break;
        case STATE_SECTION_TYPE_READ:
          firstByteOfSectionSize = b;
          stateOfReading = STATE_SECTION_SIZE_FIRST_BYTE_READ;
          break;
        case STATE_SECTION_SIZE_FIRST_BYTE_READ:
          sectionBytesToRead = firstByteOfSectionSize << 8 | b;
          stateOfReading = sectionBytesToRead == 0 ? STATE_EXPECT_NEXT : STATE_SECTION_BYTES_COUNTDOWN;
          break;
        case STATE_SECTION_BYTES_COUNTDOWN:
          sectionBytesToRead--;
          int bytesToRead = Math.min(sectionBytesToRead, limit - position);
          position += bytesToRead;
          sectionBytesToRead -= bytesToRead;
          if (sectionBytesToRead == 0) {
            stateOfReading = STATE_EXPECT_NEXT;
          }
          break;
      }
    }
    array.setPosition(position);
  }
}
