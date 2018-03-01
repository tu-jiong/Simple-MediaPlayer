package com.light.mediaplayer;

import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.view.SurfaceView;

import com.google.android.exoplayer2.ExoPlaybackException;
import com.light.library.Config;
import com.light.library.ErrorReporter;
import com.light.library.IMediaPlayer;
import com.light.library.Media;

public class MainActivity extends AppCompatActivity {

    private SurfaceView mSurfaceView;
    private IMediaPlayer mMediaPlayer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mSurfaceView = findViewById(R.id.surface_view);
        mMediaPlayer = IMediaPlayer.Factory.createMediaPlayer(this);
        mMediaPlayer.setSurface(mSurfaceView);
        Config config = new Config();
        config.setApplicationId("your application");
        config.setErrorReporter(new ErrorReporter() {
            @Override
            public void report(String uri, ExoPlaybackException error) {
                //Do something
            }
        });
//        config.setCache(true);
//        config.setCachePath("your path");
        mMediaPlayer.setConfig(config);
        mMediaPlayer.setMediaEventListener(new IMediaPlayer.MediaEventListener() {
            @Override
            public void onBuffering() {

            }

            @Override
            public void onReady(int position, int duration) {

            }

            @Override
            public void onPlay() {

            }

            @Override
            public void onPause() {

            }

            @Override
            public void onError(Exception error) {

            }

            @Override
            public void onComplete() {

            }

            @Override
            public void onPositionChanged(int position, int buffered, int duration) {

            }

            @Override
            public void onNetWorse() {

            }
        });
        mMediaPlayer.setMedia(new Media("your url"));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mMediaPlayer != null)
            mMediaPlayer.resume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mMediaPlayer != null)
            mMediaPlayer.pause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mMediaPlayer != null) {
            mMediaPlayer.stop();
            mMediaPlayer.clearSurface();
            mMediaPlayer.release();
            mMediaPlayer = null;
        }
    }
}
