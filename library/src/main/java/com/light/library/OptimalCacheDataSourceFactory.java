package com.light.library;


import android.content.Context;

import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSourceFactory;
import com.google.android.exoplayer2.upstream.TransferListener;

/**
 * Created by Tujiong on 2018/2/10.
 */

public class OptimalCacheDataSourceFactory implements DataSource.Factory {

    private final Context context;
    private final Config config;
    private final TransferListener<? super DataSource> listener;
    private final DataSource.Factory baseDataSourceFactory;

    public OptimalCacheDataSourceFactory(Context context, Config config) {
        this(context, config, null);
    }

    public OptimalCacheDataSourceFactory(Context context, Config config,
                                         TransferListener<? super DataSource> listener) {
        this.context = context.getApplicationContext();
        this.config = config;
        this.listener = listener;
        this.baseDataSourceFactory = new DefaultHttpDataSourceFactory(config.getApplicationId(), listener);
    }

    @Override
    public DataSource createDataSource() {
        return new OptimalCacheDataSource(context, config.isCache(), config.getCachePath(), listener, config.getApplicationId(), DefaultHttpDataSource.DEFAULT_CONNECT_TIMEOUT_MILLIS,
                DefaultHttpDataSource.DEFAULT_READ_TIMEOUT_MILLIS, false);
    }
}
