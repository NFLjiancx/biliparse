package com.biliparse.model;

/**
 * 画质选项（qn + 描述）
 */
public class Quality {

    public final int qn;
    public final String description;

    public Quality(int qn, String description) {
        this.qn = qn;
        this.description = description;
    }

    @Override
    public String toString() {
        return description;
    }
}
