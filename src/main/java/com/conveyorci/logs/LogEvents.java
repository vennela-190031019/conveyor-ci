package com.conveyorci.logs;

import java.util.List;

/** Wire format shared by the worker (publisher) and the API (streamer). */
public final class LogEvents {

    private LogEvents() {
    }

    /** Redis list holding the current attempt's recent lines, for clients that connect mid-run. */
    public static String bufferKey(long jobId) {
        return "conveyor:logs:buf:" + jobId;
    }

    /** Redis pub/sub channel carrying a job's live events. */
    public static String channel(long jobId) {
        return "conveyor:logs:ch:" + jobId;
    }

    public static final String CHANNEL_PATTERN = "conveyor:logs:ch:*";

    /**
     * One output line. {@code seq} increases by one per line within an attempt, which lets the
     * streamer merge the buffered snapshot with live events without gaps or duplicates.
     * {@code step} is the step position, 0 for the checkout, or -1 for messages from Conveyor itself.
     */
    public record LogLine(long seq, int step, String text) {
    }

    /** A line as stored in the Redis buffer. */
    public record BufferedLine(int attempt, long seq, int step, String text) {
        public LogLine toLine() {
            return new LogLine(seq, step, text);
        }
    }

    public enum Type { LINES, RESET, END }

    /**
     * @param status for END: the job's final status
     */
    public record LogEvent(Type type, long jobId, int attempt, List<LogLine> lines, String status) {
    }
}
