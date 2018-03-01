package com.light.mediaplayer;

import android.app.Activity;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.SurfaceView;
import android.widget.SeekBar;
import android.widget.TextView;

import com.google.android.exoplayer2.ExoPlaybackException;
import com.light.library.Config;
import com.light.library.ErrorReporter;
import com.light.library.IMediaPlayer;
import com.light.library.Media;

public class MainActivity extends Activity {

    private String url = "rtmp://live.hkstv.hk.lxdns.com/live/hks";
    private SurfaceView mSurfaceView;
    private SeekBar mSeekBar;
    private TextView mTextView;
    private IMediaPlayer mMediaPlayer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mSurfaceView = (SurfaceView) findViewById(R.id.surface_view);
        mSeekBar = (SeekBar) findViewById(R.id.seek_bar);
        mTextView = (TextView) findViewById(R.id.text_view);
        mTextView.setText(url);
        mMediaPlayer = IMediaPlayer.Factory.createMediaPlayer(this);
        mMediaPlayer.setSurface(mSurfaceView);
        Config config = new Config();
        config.setApplicationId("your application");
        config.setErrorReporter(new ErrorReporter() {
            @Override
            public void report(String uri, ExoPlaybackException error) {
                //Do something
                Log.e("tujiong", error.toString());
            }
        });
        config.setCache(true);
        config.setCachePath(Environment.getExternalStorageDirectory().getAbsolutePath() + "/m3u8-cache");
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
                mSeekBar.setProgress(position);
                mSeekBar.setMax(duration);
            }

            @Override
            public void onNetWorse() {

            }
        });
        mMediaPlayer.setMedia(new Media(url));
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
