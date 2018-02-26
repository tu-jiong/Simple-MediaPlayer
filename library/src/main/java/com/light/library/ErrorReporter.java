package com.light.library;

import com.google.android.exoplayer2.ExoPlaybackException;

/**
 * Created by Tujiong on 2018/2/26.
 */

public interface ErrorReporter {
    void report(String uri, ExoPlaybackException error);
}
