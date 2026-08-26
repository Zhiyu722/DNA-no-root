package com.zhiyu.dna.engine;

/** 进度 / 日志回调。 */
public interface Progress {
    void log(String line);
    void progress(int percent);
    void done(boolean ok, String message);
}
