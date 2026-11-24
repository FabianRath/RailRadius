package com.railradius.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Runs the build check (#10) in Node, which runs the compiled engine; on a failure its output says which line. */
class BrowserEngineCheckTest {

    private static final String CHECK_SCRIPT = "src/test/js/check.mjs";

    @Test
    void theBrowserEngineFindsWhatTheServerFinds() throws IOException, InterruptedException {
        Process node = new ProcessBuilder("node", CHECK_SCRIPT, System.getProperty("engine.dir"),
                System.getProperty("check.dir")).redirectErrorStream(true).start();

        String output = new String(node.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

        assertThat(node.waitFor()).as(output).isZero();
    }
}
