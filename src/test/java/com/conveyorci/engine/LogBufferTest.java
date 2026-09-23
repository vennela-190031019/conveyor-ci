package com.conveyorci.engine;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LogBufferTest {

    @Test
    void keepsEverythingUnderTheLimit() {
        LogBuffer buffer = new LogBuffer(100);
        buffer.appendLine("one");
        buffer.appendLine("two");
        assertThat(buffer.toString()).isEqualTo("one\ntwo\n");
    }

    @Test
    void keepsTheTailWhenOverTheLimit() {
        LogBuffer buffer = new LogBuffer(10);
        for (int i = 0; i < 20; i++) {
            buffer.appendLine("line" + i);
        }
        String out = buffer.toString();
        assertThat(out).startsWith(LogBuffer.TRUNCATED_MARKER).endsWith("line19\n");
        assertThat(out.length()).isEqualTo(LogBuffer.TRUNCATED_MARKER.length() + 10);
    }
}
