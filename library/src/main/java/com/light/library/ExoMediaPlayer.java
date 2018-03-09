package com.light.library;

import android.content.Context;
import android.graphics.PixelFormat;
import android.media.MediaMetadataRetriever;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.decoder.DecoderCounters;
import com.google.android.exoplayer2.ext.rtmp.RtmpDataSourceFactory;
import com.google.android.exoplayer2.source.ExtractorMediaSource;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.trackselection.AdaptiveTrackSelection;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.TrackSelection;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.google.android.exoplayer2.trackselection.TrackSelector;
import com.google.android.exoplayer2.upstream.BandwidthMeter;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.util.Util;
import com.google.android.exoplayer2.video.VideoRendererEventListener;

/**
 * Created by Tujiong on 2017/8/7.
 */

class ExoMediaPlayer extends AbsMediaPlayer {

    private int mPlayerState = IMediaPlayer.STATE_IDLE;
    private int mTargetState = -1;
    private int mRetry;
    private int worseCount;
    private long mPosition;
    private long mResumePos;
    private long mDuration;
    private float mSpeed;
    private String mUri;
    private Handler mHandler;
    private Config mConfig;
    private SimpleExoPlayer mExoPlayer;
    private MediaSource mMediaSource;
    private IMediaPlayer.MediaEventListener mMediaEventListener;
    private SurfaceHolder mSurfaceHolder;

    private final Runnable updateProgressAction = new Runnable() {
        @Override
        public void run() {
            updateProgress();
        }
    };

    private Player.EventListener mEventListener = new Player.EventListener() {
        @Override
        public void onTimelineChanged(Timeline timeline, Object manifest, int reason) {
        }

        @Override
        public void onTracksChanged(TrackGroupArray trackGroups, TrackSelectionArray trackSelections) {
        }

        @Override
        public void onLoadingChanged(boolean isLoading) {
        }

        @Override
        public void onPlayerStateChanged(boolean playWhenReady, int playbackState) {
            if (mMediaEventListener != null) {
                switch (playbackState) {
                    case Player.STATE_BUFFERING:
                        mTargetState = Player.STATE_BUFFERING;
                        mPlayerState = IMediaPlayer.STATE_LOADING;
                        if (isHttpUrl(mUri))
                            mMediaEventListener.onBuffering();
                        break;
                    case Player.STATE_READY:
                        mTargetState = -1;
                        mMediaEventListener.onReady((int) getCurrentPosition(), (int) getDuration());
                        if (playWhenReady) {
                            mPlayerState = IMediaPlayer.STATE_PLAY;
                            changeAudioFocus(true);
                            mMediaEventListener.onPlay();
                        } else {
                            mPlayerState = IMediaPlayer.STATE_PAUSE;
                            changeAudioFocus(false);
                            mMediaEventListener.onPause();
                        }
                        break;
                    case Player.STATE_ENDED:
                        mPlayerState = IMediaPlayer.STATE_COMPLETE;
                        mTargetState = -1;
                        stop();
                        mResumePos = 0;
                        changeAudioFocus(false);
                        drawSurface();
                        worseCount = 0;
                        mMediaEventListener.onComplete();
                        break;
                    case Player.STATE_IDLE:
                        if (!TextUtils.isEmpty(mUri)
                                && isHttpUrl(mUri)
                                && isNetworkConnected(mContext)
                                && mRetry < IMediaPlayer.MAX_RETRY_TIMES
                                && mPlayerState != IMediaPlayer.STATE_ERROR
                                && mPlayerState != IMediaPlayer.STATE_STOP
                                && mTargetState == Player.STATE_BUFFERING) {
                            retry();
                            mTargetState = -1;
                        }
                        mPlayerState = IMediaPlayer.STATE_IDLE;
                        break;
                }
            }
        }

        @Override
        public void onRepeatModeChanged(int repeatMode) {
        }

        @Override
        public void onShuffleModeEnabledChanged(boolean b) {

        }

        @Override
        public void onPlayerError(ExoPlaybackException error) {
            mPlayerState = IMediaPlayer.STATE_ERROR;
            worseCount = 0;
            drawSurface();
            if (error == null)
                return;
            if (mMediaEventListener != null)
                mMediaEventListener.onError(error);
            if (mConfig != null) {
                ErrorReporter reporter = mConfig.getErrorReporter();
                if (reporter != null)
                    reporter.report(mUri, error);
            }
        }

        @Override
        public void onPositionDiscontinuity(int i) {

        }

        @Override
        public void onPlaybackParametersChanged(PlaybackParameters playbackParameters) {
        }

        @Override
        public void onSeekProcessed() {
        }
    };

    ExoMediaPlayer(Context context) {
        super(context);
        mHandler = new Handler(Looper.getMainLooper());
        BandwidthMeter bandwidthMeter = new DefaultBandwidthMeter();
        TrackSelection.Factory videoTrackSelectionFactory = new AdaptiveTrackSelection.Factory(bandwidthMeter);
        TrackSelector trackSelector = new DefaultTrackSelector(videoTrackSelectionFactory);
        mExoPlayer = ExoPlayerFactory.newSimpleInstance(mContext, trackSelector);
        mExoPlayer.setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
        mExoPlayer.addListener(mEventListener);
        mExoPlayer.addVideoDebugListener(new VideoRendererEventListener() {
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
            }

            @Override
            public void onRenderedFirstFrame(Surface surface) {
            }

            @Override
            public void onVideoDisabled(DecoderCounters counters) {
            }
        });
    }

    @Override
    public void setConfig(Config config) {
        mConfig = config;
    }

    @Override
    public void setVideoScaleMode(int mode) {
        if (mExoPlayer != null) {
            if (mode == MODE_SCALE) {
                mExoPlayer.setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT);
            } else if (mode == MODE_CROP) {
                mExoPlayer.setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
            }
        }
    }

    @Override
    public void setMedia(Media media) {
        if (media == null)
            return;
        String uri = media.getUri();
        if (TextUtils.isEmpty(uri)) {
            if (mMediaEventListener != null)
                mMediaEventListener.onError(new Exception("uri can't be null"));
        } else {
            mPlayerState = IMediaPlayer.STATE_PREPARED;
            mRetry = 0;
            mUri = uri;
            mSpeed = media.getSpeed();
            long position = media.getPosition();
            mPosition = position;
            mResumePos = position;
            mDuration = 0;
            worseCount = 0;
            openInternal();
        }
    }

    private void retry() {
        if (!TextUtils.isEmpty(mUri)) {
            openInternal();
            mRetry++;
        }
    }

    private void openInternal() {
        if (!isHttpUrl(mUri)) {
            try {
                MediaMetadataRetriever mmr = new MediaMetadataRetriever();
                mmr.setDataSource(mUri);
                String duration = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                mDuration = Long.valueOf(duration);
            } catch (Exception e) {
                //Do nothing
            }
        }
        mMediaSource = createMediaSource();
        if (mExoPlayer != null) {
            mExoPlayer.prepare(mMediaSource);
            if (mPosition > 0)
                mExoPlayer.seekTo(mPosition);
            setSpeed(mSpeed);
            mExoPlayer.setPlayWhenReady(true);
            updateProgress();
        }
    }

    private MediaSource createMediaSource() {
        String appId = mConfig == null ? "MediaPlayer" : mConfig.getApplicationId();
        String userAgent = Util.getUserAgent(mContext, appId);
        MediaSource mediaSource;
        if (isHls()) {
            DataSource.Factory dataSourceFactory = new HlsCacheableDataSourceFactory(mContext, mConfig);
            mediaSource = new HlsMediaSource.Factory(dataSourceFactory).createMediaSource(Uri.parse(mUri));
        } else if (isRtmp()) {
            DataSource.Factory dataSourceFactory = new RtmpDataSourceFactory();
            mediaSource = new ExtractorMediaSource.Factory(dataSourceFactory).createMediaSource(Uri.parse(mUri));
        } else {
            DataSource.Factory dataSourceFactory = new DefaultDataSourceFactory(mContext, userAgent);
            mediaSource = new ExtractorMediaSource.Factory(dataSourceFactory).createMediaSource(Uri.parse(mUri));
        }
        return mediaSource;
    }

    private void drawSurface() {
        if (mSurfaceHolder != null)
            mSurfaceHolder.setFormat(PixelFormat.TRANSPARENT);
    }

    @Override
    public void resume() {
        if (mExoPlayer != null) {
            int state = mExoPlayer.getPlaybackState();
            if (state == Player.STATE_READY || state == Player.STATE_BUFFERING) {
                mExoPlayer.setPlayWhenReady(true);
            } else {
                stop();
                setMedia(new Media(mUri, mResumePos, mSpeed));
            }
        }
    }

    @Override
    public void pause() {
        if (mExoPlayer != null)
            mExoPlayer.setPlayWhenReady(false);
    }

    @Override
    public void seekTo(long position) {
        if (mExoPlayer != null && position >= 0 && position < getDuration()) {
            mPosition = position;
            mResumePos = position;
            mExoPlayer.seekTo(position);
        }
    }

    @Override
    public void setMute(boolean mute) {
        if (mExoPlayer != null) {
            if (mute)
                mExoPlayer.setVolume(0);
            else
                mExoPlayer.setVolume(1);
        }
    }

    @Override
    public long getDuration() {
        if (mExoPlayer == null) {
            return mDuration;
        } else {
            long duration = mExoPlayer.getDuration();
            if (duration < 0) {
                return mDuration;
            } else {
                return duration;
            }
        }
    }

    @Override
    public long getCurrentPosition() {
        return mExoPlayer == null ? mPosition : mExoPlayer.getCurrentPosition();
    }

    @Override
    public void setSpeed(float speed) {
        if (mExoPlayer != null) {
            mSpeed = speed;
            PlaybackParameters playbackParameters = new PlaybackParameters(speed, 1f);
            mExoPlayer.setPlaybackParameters(playbackParameters);
        }
    }

    @Override
    public float getSpeed() {
        return mExoPlayer == null ? mSpeed : mExoPlayer.getPlaybackParameters().speed;
    }

    @Override
    public boolean isPlaying() {
        return mExoPlayer != null
                && mExoPlayer.getPlayWhenReady()
                && mExoPlayer.getPlaybackState() == Player.STATE_READY;
    }

    @Override
    public void stop() {
        try {
            mPlayerState = IMediaPlayer.STATE_STOP;
            mHandler.removeCallbacksAndMessages(null);
            if (mExoPlayer != null) {
                mExoPlayer.setPlayWhenReady(false);
                mExoPlayer.stop();
                if (mMediaSource != null) {
                    try {
                        mMediaSource.releaseSource();
                    } catch (Exception e) {
                        //Do nothing
                    } finally {
                        mMediaSource = null;
                    }
                }
            }
        } catch (Exception e) {
            //Do nothing
        }
    }

    @Override
    public void release() {
        super.release();
        if (mExoPlayer != null) {
            mExoPlayer.removeListener(mEventListener);
            mExoPlayer.clearVideoSurface();
            mExoPlayer.release();
        }
        mHandler.removeCallbacksAndMessages(null);
    }

    @Override
    public void setMediaEventListener(IMediaPlayer.MediaEventListener l) {
        mMediaEventListener = l;
    }

    @Override
    public void setSurface(SurfaceView surfaceView) {
        if (surfaceView != null) {
            SurfaceHolder holder = surfaceView.getHolder();
            holder.addCallback(new SurfaceHolder.Callback() {
                @Override
                public void surfaceCreated(SurfaceHolder holder) {
                    if (mExoPlayer != null)
                        mExoPlayer.setVideoSurfaceHolder(holder);
                }

                @Override
                public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                    if (mExoPlayer != null)
                        mExoPlayer.setVideoSurfaceHolder(holder);
                }

                @Override
                public void surfaceDestroyed(SurfaceHolder holder) {
                    if (mExoPlayer != null)
                        mExoPlayer.clearVideoSurfaceHolder(holder);
                }
            });
            if (mExoPlayer != null)
                mExoPlayer.setVideoSurfaceHolder(holder);
            mSurfaceHolder = holder;
        }
    }

    @Override
    public void clearSurface() {
        if (mExoPlayer != null && mSurfaceHolder != null)
            mExoPlayer.clearVideoSurfaceHolder(mSurfaceHolder);
    }

    private boolean isHls() {
        return mUri != null && (mUri.toLowerCase().endsWith(".m3u8") || mUri.toLowerCase().contains(".m3u8?key="));
    }

    private boolean isRtmp() {
        return mUri != null && mUri.startsWith("rtmp://");
    }

    private boolean isHttpUrl(String str) {
        return !TextUtils.isEmpty(str) && (str.startsWith("http://") || str.startsWith("https://"));
    }

    private boolean isNetworkConnected(Context context) {
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo info = manager == null ? null : manager.getActiveNetworkInfo();
        return info != null && info.isAvailable() && info.isConnected();
    }

    private void updateProgress() {
        if (mExoPlayer != null) {
            int state = mExoPlayer.getPlaybackState();
            if (state != Player.STATE_IDLE) {
                if (state == Player.STATE_READY)
                    worseCount = 0;
                mPosition = mExoPlayer.getCurrentPosition();
                mResumePos = mPosition;
                long buffered = mExoPlayer.getBufferedPosition();
                if (mMediaEventListener != null && state == Player.STATE_READY && mExoPlayer.getPlayWhenReady())
                    mMediaEventListener.onPositionChanged((int) mPosition, (int) buffered, (int) getDuration());
                int buffer = Math.min(DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS, DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS);
                if (buffered - mPosition < buffer && state == Player.STATE_BUFFERING && mExoPlayer.getPlayWhenReady()) {
                    if (worseCount > 15) {
                        if (mMediaEventListener != null)
                            mMediaEventListener.onNetWorse();
                    }
                    worseCount++;
                }
            }
        }
        mHandler.postDelayed(updateProgressAction, 1000);
    }
}
