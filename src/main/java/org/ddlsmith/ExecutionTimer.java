package org.ddlsmith;

import java.util.concurrent.TimeUnit;

public final class ExecutionTimer {

    private long startTime;
    private long endTime;

    public ExecutionTimer start() {
        startTime = System.nanoTime();
        return this;
    }

    public ExecutionTimer end() {
        endTime = System.nanoTime();
        return this;
    }

    public long getTimeElapsed() {
        return endTime - startTime;
    }

    public String asString() {
        long timeNs = getTimeElapsed();
        if (timeNs == 0) {
            return "0 ms";
        }
        long timeMs = TimeUnit.NANOSECONDS.toMillis(timeNs);
        if (timeMs < 1000) {
            return timeMs + " ms";
        }
        long timeS = TimeUnit.MILLISECONDS.toSeconds(timeMs);
        if (timeS < 60) {
            return timeS + " s";
        }
        return TimeUnit.SECONDS.toMinutes(timeS) + " min";
    }
}
