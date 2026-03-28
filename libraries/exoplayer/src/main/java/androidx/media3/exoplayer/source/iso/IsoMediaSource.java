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
package androidx.media3.exoplayer.source.iso;

import static com.google.common.base.Preconditions.checkNotNull;

import android.net.Uri;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaTitle;
import androidx.media3.common.Timeline;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.TransferListener;
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider;
import androidx.media3.exoplayer.source.CompositeMediaSource;
import androidx.media3.exoplayer.source.ConcatenatingMediaSource2;
import androidx.media3.exoplayer.source.MediaPeriod;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.upstream.Allocator;
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy;
import androidx.media3.exoplayer.upstream.Loader;
import java.io.IOException;
import java.util.List;

public final class IsoMediaSource extends CompositeMediaSource<Void> {

  private static final Void CHILD_ID = null;
  private static final int MIN_RETRY_COUNT = 1;

  private final DataSource.Factory dataSourceFactory;
  private final MediaItem mediaItem;

  @Nullable
  private volatile List<MediaTitle> availableTitles;

  @Nullable
  private ConcatenatingMediaSource2 innerSource;
  @Nullable
  private Loader loader;

  private IsoMediaSource(MediaItem mediaItem, DataSource.Factory dataSourceFactory) {
    this.mediaItem = mediaItem;
    this.dataSourceFactory = dataSourceFactory;
  }

  public static int parseTitleIndex(Uri uri) {
    String fragment = uri.getFragment();
    if (fragment != null && fragment.startsWith("title=")) {
      try {
        return Integer.parseInt(fragment.substring("title=".length()));
      } catch (NumberFormatException ignored) {
      }
    }
    return -1;
  }

  public int getSelectedTitleIndex() {
    return mediaItem.localConfiguration != null ? parseTitleIndex(mediaItem.localConfiguration.uri) : -1;
  }

  @Nullable
  public List<MediaTitle> getMediaTitles() {
    return availableTitles;
  }

  @NonNull
  @Override
  public MediaItem getMediaItem() {
    return mediaItem;
  }

  @Override
  protected void prepareSourceInternal(@Nullable TransferListener mediaTransferListener) {
    super.prepareSourceInternal(mediaTransferListener);
    loader = new Loader("IsoMediaSource");
    Uri isoUri = mediaItem.localConfiguration != null ? mediaItem.localConfiguration.uri : Uri.EMPTY;
    IsoParseLoadable loadable = new IsoParseLoadable(mediaItem, dataSourceFactory, isoUri);
    loader.startLoading(loadable, new ParseCallback(), MIN_RETRY_COUNT);
  }

  @Override
  public void maybeThrowSourceInfoRefreshError() throws IOException {
    if (loader != null) {
      loader.maybeThrowError();
    }
    super.maybeThrowSourceInfoRefreshError();
  }

  @Override
  protected void onChildSourceInfoRefreshed(Void childSourceId, @NonNull MediaSource mediaSource, @NonNull Timeline newTimeline) {
    refreshSourceInfo(newTimeline);
  }

  @NonNull
  @Override
  public MediaPeriod createPeriod(@NonNull MediaPeriodId id, @NonNull Allocator allocator, long startPositionUs) {
    return checkNotNull(innerSource).createPeriod(id, allocator, startPositionUs);
  }

  @Override
  public void releasePeriod(@NonNull MediaPeriod mediaPeriod) {
    checkNotNull(innerSource).releasePeriod(mediaPeriod);
  }

  @Override
  protected void releaseSourceInternal() {
    super.releaseSourceInternal();
    if (loader != null) {
      loader.release();
      loader = null;
    }
    innerSource = null;
  }

  public static final class Factory implements MediaSource.Factory {

    private final DataSource.Factory dataSourceFactory;

    public Factory(DataSource.Factory dataSourceFactory) {
      this.dataSourceFactory = dataSourceFactory;
    }

    @NonNull
    @Override
    public MediaSource createMediaSource(@NonNull MediaItem mediaItem) {
      return new IsoMediaSource(mediaItem, dataSourceFactory);
    }

    @NonNull
    @Override
    public MediaSource.Factory setDrmSessionManagerProvider(@NonNull DrmSessionManagerProvider drmSessionManagerProvider) {
      return this;
    }

    @NonNull
    @Override
    public MediaSource.Factory setLoadErrorHandlingPolicy(@NonNull LoadErrorHandlingPolicy loadErrorHandlingPolicy) {
      return this;
    }

    @NonNull
    @Override
    public @C.ContentType int[] getSupportedTypes() {
      return new int[]{C.CONTENT_TYPE_OTHER};
    }
  }

  private final class ParseCallback implements Loader.Callback<IsoParseLoadable> {

    @Override
    public void onLoadCompleted(IsoParseLoadable loadable, long elapsedRealtimeMs, long loadDurationMs) {
      availableTitles = loadable.titles;
      innerSource = (ConcatenatingMediaSource2) checkNotNull(loadable.result);
      prepareChildSource(CHILD_ID, innerSource);
    }

    @Override
    public void onLoadCanceled(@NonNull IsoParseLoadable loadable, long elapsedRealtimeMs, long loadDurationMs, boolean released) {
    }

    @NonNull
    @Override
    public Loader.LoadErrorAction onLoadError(@NonNull IsoParseLoadable loadable, long elapsedRealtimeMs, long loadDurationMs, @NonNull IOException error, int errorCount) {
      return Loader.DONT_RETRY_FATAL;
    }
  }
}
