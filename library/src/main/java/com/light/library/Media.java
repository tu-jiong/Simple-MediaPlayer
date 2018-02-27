package com.light.library;

/**
 * Created by Tujiong on 2018/2/27.
 */

public class Media {

    private long position;
    private float speed;
    private String uri;

    public Media(String uri) {
        this(uri, 0, 1.0f);
    }

    public Media(String uri, long position, float speed) {
        this.uri = uri;
        this.position = position;
        this.speed = speed;
    }

    public long getPosition() {
        return position;
    }

    public float getSpeed() {
        return speed;
    }

    public String getUri() {
        return uri;
    }
}
