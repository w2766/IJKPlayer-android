/*
 * Copyright (C) 2015 Bilibili
 * Copyright (C) 2015 Zhang Rui <bbcallen@gmail.com>
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

package tv.danmaku.ijk.media.exo;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.Size;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.DefaultRenderersFactory;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.audio.AudioRendererEventListener;
import com.google.android.exoplayer2.decoder.DecoderCounters;
import com.google.android.exoplayer2.ext.rtmp.RtmpDataSourceFactory;
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory;
import com.google.android.exoplayer2.source.ExtractorMediaSource;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.source.dash.DashMediaSource;
import com.google.android.exoplayer2.source.dash.DefaultDashChunkSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.source.smoothstreaming.DefaultSsChunkSource;
import com.google.android.exoplayer2.source.smoothstreaming.SsMediaSource;
import com.google.android.exoplayer2.trackselection.AdaptiveTrackSelection;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.TrackSelection;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSourceFactory;
import com.google.android.exoplayer2.util.Util;
import com.google.android.exoplayer2.video.VideoRendererEventListener;

import java.io.FileDescriptor;
import java.util.Map;

import tv.danmaku.ijk.media.exo.demo.EventLogger;
import tv.danmaku.ijk.media.exo.demo.player.SimpleExoPlayer2;
import tv.danmaku.ijk.media.player.AbstractMediaPlayer;
import tv.danmaku.ijk.media.player.IMediaPlayer;
import tv.danmaku.ijk.media.player.MediaInfo;
import tv.danmaku.ijk.media.player.misc.IjkTrackInfo;

public class IjkExoMediaPlayer extends AbstractMediaPlayer implements Player.EventListener,
        VideoRendererEventListener,
        AudioRendererEventListener {
    private static final String TAG = "IjkExoMediaPlayer";

    private Context mAppContext;
    private SimpleExoPlayer2 mInternalPlayer;
    private EventLogger mEventLogger;
    private DefaultRenderersFactory renderersFactory;
    private MediaSource mMediaSource;
    private DefaultTrackSelector mTrackSelector;
    private String mDataSource;
    private int mVideoWidth;
    private int mVideoHeight;
    private Surface mSurface;
    private Handler mainHandler;
    private int lastReportedPlaybackState;
    private boolean lastReportedPlayWhenReady;
    private boolean mIsPrepareing = true;
    private boolean mIsBuffering = false;

    private int audioSessionId = C.AUDIO_SESSION_ID_UNSET;


    public IjkExoMediaPlayer(Context context) {
        mAppContext = context.getApplicationContext();
        Looper eventLooper = Looper.myLooper() != null ? Looper.myLooper() : Looper.getMainLooper();
        mainHandler = new Handler(eventLooper);
        lastReportedPlaybackState = Player.STATE_IDLE;
    }

    @Override
    public void setDisplay(SurfaceHolder sh) {
        if (sh == null)
            setSurface(null);
        else
            setSurface(sh.getSurface());
    }

    @Override
    public void setSurface(Surface surface) {
        mSurface = surface;
        if (mInternalPlayer != null)
            mInternalPlayer.setVideoSurface(surface);
    }

    @Override
    public void setDataSource(Context context, Uri uri) {
        mDataSource = uri.toString();
        mMediaSource = getMediaSource(false);
    }

    @Override
    public void setDataSource(Context context, Uri uri, Map<String, String> headers) {
        // TODO: handle headers
        setDataSource(context, uri);
    }

    @Override
    public void setDataSource(String path) {
        setDataSource(mAppContext, Uri.parse(path));
    }

    @Override
    public void setDataSource(FileDescriptor fd) {
        // TODO: no support
        throw new UnsupportedOperationException("no support");
    }

    @Override
    public String getDataSource() {
        return mDataSource;
    }

    @Override
    public void prepareAsync() throws IllegalStateException {
        if (mInternalPlayer != null)
            throw new IllegalStateException("can't prepare a prepared player");
        TrackSelection.Factory videoTrackSelectionFactory =
                new AdaptiveTrackSelection.Factory(new DefaultBandwidthMeter());
        mTrackSelector = new DefaultTrackSelector(videoTrackSelectionFactory);

        mEventLogger = new EventLogger(mTrackSelector);

        boolean preferExtensionDecoders = true;
        boolean useExtensionRenderers = true;//是否开启扩展
        @DefaultRenderersFactory.ExtensionRendererMode int extensionRendererMode = useExtensionRenderers
                        ? (preferExtensionDecoders ? DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                        : DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
                        : DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF;

        renderersFactory = new DefaultRenderersFactory(mAppContext, null, extensionRendererMode);
        DefaultLoadControl loadControl = new DefaultLoadControl();
        mInternalPlayer = new SimpleExoPlayer2(renderersFactory, mTrackSelector, loadControl);
        mInternalPlayer.addListener(this);
        mInternalPlayer.setVideoDebugListener(this);
        mInternalPlayer.setAudioDebugListener(this);
        mInternalPlayer.addListener(mEventLogger);

        if (mSurface != null)
            mInternalPlayer.setVideoSurface(mSurface);

        mInternalPlayer.prepare(mMediaSource);
        mInternalPlayer.setPlayWhenReady(false);
    }

    @Override
    public void start() throws IllegalStateException {
        if (mInternalPlayer == null)
            return;
        mInternalPlayer.setPlayWhenReady(true);
    }

    @Override
    public void stop() throws IllegalStateException {
        if (mInternalPlayer == null)
            return;
        mInternalPlayer.release();
    }

    @Override
    public void pause() throws IllegalStateException {
        if (mInternalPlayer == null)
            return;
        mInternalPlayer.setPlayWhenReady(false);
    }

    @Override
    public void setWakeMode(Context context, int mode) {
        // FIXME: implement
    }

    @Override
    public void setScreenOnWhilePlaying(boolean screenOn) {
        // TODO: do nothing
    }

    @Override
    public IjkTrackInfo[] getTrackInfo() {
        // TODO: implement
        return null;
    }

    @Override
    public int getVideoWidth() {
        return mVideoWidth;
    }

    @Override
    public int getVideoHeight() {
        return mVideoHeight;
    }

    @Override
    public boolean isPlaying() {
        if (mInternalPlayer == null)
            return false;
        int state = mInternalPlayer.getPlaybackState();
        switch (state) {
            case Player.STATE_BUFFERING:
            case Player.STATE_READY:
                return mInternalPlayer.getPlayWhenReady();
            case Player.STATE_IDLE:
            case Player.STATE_ENDED:
            default:
                return false;
        }
    }

    @Override
    public void seekTo(long msec) throws IllegalStateException {
        if (mInternalPlayer == null)
            return;
        mInternalPlayer.seekTo(msec);
    }

    @Override
    public long getCurrentPosition() {
        if (mInternalPlayer == null)
            return 0;
        return mInternalPlayer.getCurrentPosition();
    }

    @Override
    public long getDuration() {
        if (mInternalPlayer == null)
            return 0;
        return mInternalPlayer.getDuration();
    }

    @Override
    public int getVideoSarNum() {
        return 1;
    }

    @Override
    public int getVideoSarDen() {
        return 1;
    }

    @Override
    public void reset() {
        if (mInternalPlayer != null) {
            mInternalPlayer.release();
//            mInternalPlayer.removeListener(this);
            mInternalPlayer = null;
        }

        mSurface = null;
        mDataSource = null;
        mVideoWidth = 0;
        mVideoHeight = 0;
    }

    @Override
    public void setLooping(boolean looping) {
        // TODO: no support
        throw new UnsupportedOperationException("no support");
    }

    @Override
    public boolean isLooping() {
        // TODO: no support
        return false;
    }

    @Override
    public void setVolume(float leftVolume, float rightVolume) {
        mInternalPlayer.setVolume((leftVolume + rightVolume) / 2);
    }

    @Override
    public int getAudioSessionId() {
        return audioSessionId;
    }

    @Override
    public MediaInfo getMediaInfo() {
        // TODO: no support
        return null;
    }

    @Override
    public void setLogEnabled(boolean enable) {
        // do nothing
    }

    @Override
    public boolean isPlayable() {
        return true;
    }

    @Override
    public void setAudioStreamType(int streamtype) {
        // do nothing
    }

    @Override
    public void setKeepInBackground(boolean keepInBackground) {
        // do nothing
    }

    @Override
    public void release() {
        if (mInternalPlayer != null) {
            reset();
            mEventLogger = null;
        }
    }

    public void stopPlayback() {
        mInternalPlayer.stop();
    }

    /**
     * 倍速播放
     *
     * @param speed 倍速播放，默认为1
     * @param pitch 音量缩放，默认为1，修改会导致声音变调
     */
    public void setSpeed(@Size(min = 0) float speed, @Size(min = 0) float pitch) {
        PlaybackParameters playbackParameters = new PlaybackParameters(speed, pitch);
        mInternalPlayer.setPlaybackParameters(playbackParameters);
    }

    public int getBufferedPercentage() {
        if (mInternalPlayer == null)
            return 0;

        return mInternalPlayer.getBufferedPercentage();
    }

    public static final int TYPE_RTMP = 4;
    /**
     * Makes a best guess to infer the type from a file name.
     *
     * @param fileName Name of the file. It can include the path of the file.
     * @return The content type.
     */
    @C.ContentType
    private int inferContentType(String fileName) {
        fileName = Util.toLowerInvariant(fileName);
        if (fileName.endsWith(".mpd")) {
            return C.TYPE_DASH;
        } else if (fileName.endsWith(".m3u8")) {
            return C.TYPE_HLS;
        } else if (fileName.endsWith(".ism") || fileName.endsWith(".isml")
                || fileName.endsWith(".ism/manifest") || fileName.endsWith(".isml/manifest")) {
            return C.TYPE_SS;
        } else if (fileName.startsWith("rtmp:")) {
            return TYPE_RTMP;
        }  else {
            return C.TYPE_OTHER;
        }
    }

    private MediaSource getMediaSource(boolean preview) {
        Uri contentUri = Uri.parse(mDataSource);
        int contentType = inferContentType(mDataSource);
        switch (contentType) {
            case C.TYPE_SS:
                return new SsMediaSource(contentUri, new DefaultDataSourceFactory(mAppContext, null,
                        getHttpDataSourceFactory(preview)),
                        new DefaultSsChunkSource.Factory(getDataSourceFactory(preview)),
                        mainHandler, null);
            case C.TYPE_DASH:
                return new DashMediaSource(contentUri,
                        new DefaultDataSourceFactory(mAppContext, null,
                                getHttpDataSourceFactory(preview)),
                        new DefaultDashChunkSource.Factory(getDataSourceFactory(preview)),
                        mainHandler, null);
            case C.TYPE_HLS:
                return new HlsMediaSource(contentUri, getDataSourceFactory(preview), mainHandler, null);
            case TYPE_RTMP:
                RtmpDataSourceFactory rtmpDataSourceFactory = new RtmpDataSourceFactory(null);
                return new ExtractorMediaSource(contentUri, rtmpDataSourceFactory,
                        new DefaultExtractorsFactory(), mainHandler, null);
            case C.TYPE_OTHER:
            default:
                return new ExtractorMediaSource(contentUri, getDataSourceFactory(preview),
                        new DefaultExtractorsFactory(), mainHandler, null);
        }
    }

    private DataSource.Factory getDataSourceFactory(boolean preview) {
        return new DefaultDataSourceFactory(mAppContext, preview ? null : new DefaultBandwidthMeter(),
                getHttpDataSourceFactory(preview));
    }

    private DataSource.Factory getHttpDataSourceFactory(boolean preview) {
        return new DefaultHttpDataSourceFactory(Util.getUserAgent(mAppContext,
                TAG), preview ? null : new DefaultBandwidthMeter());
    }

    @Override
    public void onTimelineChanged(Timeline timeline, Object manifest) {

    }

    @Override
    public void onTracksChanged(TrackGroupArray trackGroups, TrackSelectionArray trackSelections) {

    }

    @Override
    public void onLoadingChanged(boolean isLoading) {

    }

    @Override
    public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
        //重新播放状态顺序为：STATE_IDLE -》STATE_BUFFERING -》STATE_READY
        //缓冲时顺序为：STATE_BUFFERING -》STATE_READY
        Log.e(TAG, "onPlayerStateChanged: playWhenReady = " + playWhenReady + ", playbackState = " + playbackState);
        if (lastReportedPlayWhenReady != playWhenReady || lastReportedPlaybackState != playbackState) {
            if (mIsBuffering) {
                switch (playbackState) {
                    case Player.STATE_ENDED:
                    case Player.STATE_READY:
                        notifyOnInfo(IMediaPlayer.MEDIA_INFO_BUFFERING_END, mInternalPlayer.getBufferedPercentage());
                        mIsBuffering = false;
                        break;
                }
            }

            if (mIsPrepareing) {
                switch (playbackState) {
                    case Player.STATE_READY:
                        notifyOnPrepared();
                        mIsPrepareing = false;
                        break;
                }
            }

            switch (playbackState) {
                case Player.STATE_BUFFERING:
                    notifyOnInfo(IMediaPlayer.MEDIA_INFO_BUFFERING_START, mInternalPlayer.getBufferedPercentage());
                    mIsBuffering = true;
                    break;
                case Player.STATE_READY:
                    break;
                case Player.STATE_ENDED:
                    notifyOnCompletion();
                    break;
                default:
                    break;
            }
        }
        lastReportedPlayWhenReady = playWhenReady;
        lastReportedPlaybackState = playbackState;
    }

    @Override
    public void onRepeatModeChanged(int repeatMode) {

    }

    @Override
    public void onPlayerError(ExoPlaybackException error) {
        notifyOnError(IMediaPlayer.MEDIA_ERROR_UNKNOWN, IMediaPlayer.MEDIA_ERROR_UNKNOWN);
    }

    @Override
    public void onPositionDiscontinuity() {

    }

    @Override
    public void onPlaybackParametersChanged(PlaybackParameters playbackParameters) {

    }

    /////////////////////////////////////AudioRendererEventListener/////////////////////////////////////////////
    @Override
    public void onAudioEnabled(DecoderCounters counters) {

    }

    @Override
    public void onAudioSessionId(int audioSessionId) {
        this.audioSessionId = audioSessionId;
    }

    @Override
    public void onAudioDecoderInitialized(String decoderName, long initializedTimestampMs, long initializationDurationMs) {

    }

    @Override
    public void onAudioInputFormatChanged(Format format) {

    }

    @Override
    public void onAudioTrackUnderrun(int bufferSize, long bufferSizeMs, long elapsedSinceLastFeedMs) {

    }

    @Override
    public void onAudioDisabled(DecoderCounters counters) {
        audioSessionId = C.AUDIO_SESSION_ID_UNSET;
    }

    /////////////////////////////////////VideoRendererEventListener/////////////////////////////////////////////
    @Override
    public void onVideoEnabled(DecoderCounters counters) {

    }

    @Override
    public void onVideoDecoderInitialized(String decoderName, long initializedTimestampMs, long initializationDurationMs) {

    }

    @Override
    public void onVideoInputFormatChanged(Format format) {

    }

    @Override
    public void onDroppedFrames(int count, long elapsedMs) {

    }

    @Override
    public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees, float pixelWidthHeightRatio) {
        mVideoWidth = width;
        mVideoHeight = height;
        notifyOnVideoSizeChanged(width, height, 1, 1);
        if (unappliedRotationDegrees > 0)
            notifyOnInfo(IMediaPlayer.MEDIA_INFO_VIDEO_ROTATION_CHANGED, unappliedRotationDegrees);
    }

    @Override
    public void onRenderedFirstFrame(Surface surface) {

    }

    @Override
    public void onVideoDisabled(DecoderCounters counters) {

    }
}
