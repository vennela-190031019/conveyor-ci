package com.conveyorci.engine;

/**
 * Collects a step's output, keeping only the most recent {@code maxChars} characters.
 * The tail is what matters when a step fails, and the cap stops one noisy step from
 * bloating the database. Thread-safe: output is appended from a reader thread.
 */
public final class LogBuffer {

    static final String TRUNCATED_MARKER = "[... earlier output truncated ...]\n";

    private final int maxChars;
    private final StringBuilder buffer = new StringBuilder();
    private boolean truncated;

    public LogBuffer(int maxChars) {
        this.maxChars = maxChars;
    }

    public synchronized void appendLine(String line) {
        buffer.append(line).append('\n');
        int overflow = buffer.length() - maxChars;
        if (overflow > 0) {
            buffer.delete(0, overflow);
            truncated = true;
        }
    }

    @Override
    public synchronized String toString() {
        return truncated ? TRUNCATED_MARKER + buffer : buffer.toString();
    }
}
