package com.light.library;

import android.content.Context;
import android.view.SurfaceView;

/**
 * Created by Tujiong on 2017/8/4.
 */

public interface IMediaPlayer {

    class Factory {
        public static IMediaPlayer createMediaPlayer(Context context) {
            /**
             * recommend to use ExoPlayer,presented by google,big brand,reliable
             */
            return new ExoMediaPlayer(context);
        }
    }

    int MAX_RETRY_TIMES = 30;
    int STATE_PREPARED = 0;
    int STATE_LOADING = 1;
    int STATE_PLAY = 2;
    int STATE_PAUSE = 3;
    int STATE_STOP = 4;
    int STATE_COMPLETE = 5;
    int STATE_ERROR = 6;
    int STATE_IDLE = 7;

    void setMediaSource(String uri, long position, float speed);

    void play();

    void pause();

    void stop();

    boolean isPlaying();

    long getCurrentPosition();

    long getDuration();

    void setSpeed(float speed);

    float getSpeed();

    void seekTo(long position);

    void setMute(boolean mute);

    boolean isPlayerRunning();

    void setSurface(SurfaceView surfaceView);

    void clearSurface(SurfaceView surfaceView);

    void setMediaEventListener(MediaEventListener listener);

    void release();

    void setErrorReporter(ErrorReporter errorReporter);

    interface MediaEventListener {
        void onBuffering();

        void onReady(int position, int duration);

        void onPlay();

        void onPause();

        void onError(Exception error);

        void onComplete();

        void onPositionChanged(int position, int duration);

        void onNetWorse();
    }
}
