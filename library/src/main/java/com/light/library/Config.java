package com.light.library;

import android.text.TextUtils;

/**
 * Created by Tujiong on 2018/2/27.
 */

public class Config {

    private boolean cache;
    private String cachePath;
    private String applicationId;
    private ErrorReporter errorReporter;

    public boolean isCache() {
        return cache;
    }

    public void setCache(boolean cache) {
        this.cache = cache;
    }

    public String getCachePath() {
        return cachePath;
    }

    public void setCachePath(String cachePath) {
        if (TextUtils.isEmpty(cachePath))
            throw new RuntimeException("cachePath can't null");
        this.cachePath = cachePath;
    }

    public String getApplicationId() {
        return applicationId;
    }

    public void setApplicationId(String applicationId) {
        this.applicationId = applicationId;
    }

    public ErrorReporter getErrorReporter() {
        return errorReporter;
    }

    public void setErrorReporter(ErrorReporter errorReporter) {
        this.errorReporter = errorReporter;
    }
}
