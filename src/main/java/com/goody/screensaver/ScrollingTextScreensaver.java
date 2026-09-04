package com.goody.screensaver;

/**
 * Compatibility {@code main} so older run commands still work. All behaviour
 * lives in {@link MarqueeSaver}; this class only forwards argv.
 */
public final class ScrollingTextScreensaver {

    private ScrollingTextScreensaver() {
    }

    public static void main(String[] args) {
        MarqueeSaver.main(args);
    }
}
