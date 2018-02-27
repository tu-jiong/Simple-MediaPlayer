package com.light.library;

/**
 * Created by Tujiong on 2018/2/27.
 */

public class Config {

    private String applicationId;
    private ErrorReporter errorReporter;

    public Config(String applicationId, ErrorReporter errorReporter) {
        this.applicationId = applicationId;
        this.errorReporter = errorReporter;
    }

    public String getApplicationId() {
        return applicationId;
    }

    public ErrorReporter getErrorReporter() {
        return errorReporter;
    }
}
