package dev.agentos.smoketest;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class GreetingTextTest {
    @Test public void suppliesExpectedBuildConfirmation() {
        assertEquals("Android APK build: OK", GreetingText.message());
    }
}
