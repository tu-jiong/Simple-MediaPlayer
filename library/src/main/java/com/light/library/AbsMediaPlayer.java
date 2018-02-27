package com.light.library;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.telephony.TelephonyManager;

/**
 * Created by Tujiong on 2017/9/7.
 */

public abstract class AbsMediaPlayer implements IMediaPlayer {

    protected boolean mWasPlaying;
    protected boolean mWasLossPlaying;

    protected Context mContext;

    protected final BroadcastReceiver mCallReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            switch (intent.getAction()) {
                case Intent.ACTION_NEW_OUTGOING_CALL:
                    pause();
                    break;
                case TelephonyManager.ACTION_PHONE_STATE_CHANGED:
                    TelephonyManager telephony = (TelephonyManager) context.getSystemService(
                            Context.TELEPHONY_SERVICE);
                    switch (telephony.getCallState()) {
                        case TelephonyManager.CALL_STATE_RINGING://来电
                            if (isPlaying()) {
                                mWasPlaying = true;
                                pause();
                            } else {
                                mWasPlaying = false;
                            }
                            break;
                        case TelephonyManager.CALL_STATE_IDLE://挂断
                            if (mWasPlaying)
                                resume();
                            break;
                    }
                    break;
            }
        }
    };

    protected AudioManager.OnAudioFocusChangeListener mAudioFocusChangeListener = new AudioManager.OnAudioFocusChangeListener() {
        @Override
        public void onAudioFocusChange(int focusChange) {
            switch (focusChange) {
                case AudioManager.AUDIOFOCUS_LOSS:
                    changeAudioFocus(false);
                    if (isPlaying()) {
                        mWasLossPlaying = true;
                        pause();
                    }
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT:
                    break;
                case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK:
                    break;
                case AudioManager.AUDIOFOCUS_GAIN:
                    if (mWasLossPlaying) {
                        resume();
                        mWasLossPlaying = false;
                    }
                    break;
            }
        }
    };

    AbsMediaPlayer(Context context) {
        mContext = context;
        IntentFilter telephonyFilter = new IntentFilter();
        telephonyFilter.setPriority(IntentFilter.SYSTEM_HIGH_PRIORITY);
        telephonyFilter.addAction(Intent.ACTION_NEW_OUTGOING_CALL);
        telephonyFilter.addAction(TelephonyManager.ACTION_PHONE_STATE_CHANGED);
        context.registerReceiver(mCallReceiver, telephonyFilter);
    }

    protected void changeAudioFocus(boolean flag) {
        AudioManager audioManager = (AudioManager) mContext.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null)
            return;
        if (flag) {
            int result = audioManager.requestAudioFocus(mAudioFocusChangeListener,
                    AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                audioManager.setParameters("bgm_state=true");
            }
        } else {
            audioManager.abandonAudioFocus(mAudioFocusChangeListener);
            audioManager.setParameters("bgm_state=false");
        }
    }

    @Override
    public void release() {
        changeAudioFocus(false);
        mContext.unregisterReceiver(mCallReceiver);
    }
}
