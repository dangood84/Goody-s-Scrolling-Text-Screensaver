package com.goody.screensaver;

import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.text.AttributedCharacterIterator;
import java.text.AttributedString;
import javax.swing.JPanel;
import javax.swing.Timer;

/**
 * View + animation loop. Reads appearance/speed from {@link ScreensaverConfig}
 * on every paint (so the settings preview updates live) and keeps only
 * <em>transient</em> motion state here: {@code x}, {@code stride}, timestamps.
 *
 * <p>Swing does not run a {@code while (true)} game loop. A {@link Timer}
 * posts {@code ActionEvent}s on the EDT; {@code onFrame} mutates {@code x}
 * then calls {@code repaint()}, which later causes {@code paintComponent}.
 */
public final class MarqueePanel extends JPanel {

    static final int TARGET_FPS = 60;
    private static final int FRAME_DELAY_MS = Math.round(1000f / TARGET_FPS);

    private final ScreensaverConfig config;
    private final Timer timer;

    /** Horizontal origin of the primary text copy; decreases as the marquee moves left. */
    private double x;
    /**
     * Distance from one tiled copy of the message to the next (text width + gap).
     * Updated during paint once font metrics are known; used on the next tick to wrap {@code x}.
     */
    private float stride = 1f;
    /** Previous {@link System#nanoTime()} sample; 0 means “no delta yet”. */
    private long lastNanos;
    /** After the first layout we seed {@code x} at the right edge so text enters from off-screen. */
    private boolean startedFromRight;

    public MarqueePanel(ScreensaverConfig config) {
        this.config = config;
        // Opaque + double-buffered: Swing paints into an off-screen image then blits it,
        // which avoids flicker when we fill the background every frame.
        setOpaque(true);
        setDoubleBuffered(true);
        setBackground(config.getBackgroundColor());
        setFocusable(true);

        // javax.swing.Timer fires on the EDT (unlike java.util.Timer). Coalesce
        // drops extra ticks if the EDT was busy so we do not queue a backlog of frames.
        timer = new Timer(FRAME_DELAY_MS, event -> onFrame());
        timer.setCoalesce(true);
        timer.setRepeats(true);
    }

    public void start() {
        lastNanos = 0L;
        if (!timer.isRunning()) {
            timer.start();
        }
    }

    public void stop() {
        timer.stop();
        lastNanos = 0L;
    }

    public boolean isRunning() {
        return timer.isRunning();
    }

    /**
     * Called by AWT when this panel is plugged into a realized window (peer created).
     * That is the safe moment to start animating: width/height are becoming meaningful
     * and the panel will actually receive paint events.
     */
    @Override
    public void addNotify() {
        super.addNotify();
        start();
    }

    /** Mirror of addNotify: stop the timer so a hidden/disposed panel does not keep the EDT busy. */
    @Override
    public void removeNotify() {
        stop();
        super.removeNotify();
    }

    /**
     * Update pass. Does not draw. Mutates {@code x}, then {@code repaint()} marks the
     * component dirty; Swing will call {@code paintComponent} later on this same EDT.
     */
    private void onFrame() {
        long now = System.nanoTime();
        if (lastNanos == 0L) {
            // First tick: establish a baseline so the first delta is not “since JVM start”.
            lastNanos = now;
            repaint();
            return;
        }

        double elapsedSeconds = (now - lastNanos) / 1_000_000_000.0;
        lastNanos = now;
        // Cap dt so a breakpoint, sleep, or stalled EDT cannot fling the text across the screen.
        elapsedSeconds = Math.min(elapsedSeconds, 0.05);

        // Velocity * time, not a fixed “pixels per tick”, so 140 px/s stays honest if the
        // timer jitters (17 ms vs 25 ms).
        x -= config.getPixelsPerSecond() * elapsedSeconds;
        while (stride > 0f && x < -stride) {
            x += stride;
        }
        repaint();
    }

    /**
     * Draw pass. Swing has already cleared/prepared the clip; we still fill the background
     * ourselves so a colour change in config is visible immediately. {@code Graphics} is
     * a throwaway context for this paint — {@code create()}/{@code dispose()} keeps hints
     * and transforms from leaking into other components.
     */
    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            // Fractional metrics let glyphs sit on sub-pixel x, which matches our double-precision x.
            g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

            g2.setColor(config.getBackgroundColor());
            g2.fillRect(0, 0, getWidth(), getHeight());

            if (getWidth() <= 0 || getHeight() <= 0) {
                return;
            }

            AttributedString attributed = createAttributedText();
            AttributedCharacterIterator iterator = attributed.getIterator();
            // FontRenderContext captures the same anti-alias / fractional settings as g2,
            // so measured width matches what will actually be drawn.
            FontRenderContext frc = g2.getFontRenderContext();
            TextLayout layout = new TextLayout(iterator, frc);

            float textWidth = layout.getAdvance();
            float gap = Math.max(96f, layout.getAscent() * 2.5f);
            stride = Math.max(1f, textWidth + gap);

            if (!startedFromRight) {
                x = getWidth();
                startedFromRight = true;
            }

            // TextLayout.draw uses a baseline, not the top of the glyph box.
            float baselineY = (getHeight() + layout.getAscent() - layout.getDescent()) / 2f;
            float drawX = (float) x;
            // Walk left until we start off-screen, then stamp copies every stride so the
            // band never has a hole as x wraps.
            while (drawX > 0f) {
                drawX -= stride;
            }
            while (drawX < getWidth()) {
                layout.draw(g2, drawX, baselineY);
                drawX += stride;
            }
        } finally {
            g2.dispose();
        }
    }

    /**
     * Builds the marquee string from a custom {@link Font}, then applies bold,
     * italic, and underline through {@link TextAttribute} on an
     * {@link AttributedString}.
     *
     * <p>Do not also put that Font on {@code TextAttribute.FONT}: if FONT is
     * present, Java ignores FAMILY/WEIGHT/POSTURE/SIZE. Underline is a separate
     * decoration, so it still applied when FONT was set — which looked like
     * “only underline works.”
     */
    private AttributedString createAttributedText() {
        String text = config.getMessage();
        if (text == null || text.isBlank()) {
            // AttributedString rejects empty strings; a space still has a layout.
            text = " ";
        }

        Font customFont = new Font(config.getFontFamily(), Font.PLAIN, config.getFontSize());
        AttributedString attributed = new AttributedString(text);
        attributed.addAttribute(TextAttribute.FAMILY, customFont.getFamily());
        attributed.addAttribute(TextAttribute.SIZE, (float) customFont.getSize());
        attributed.addAttribute(TextAttribute.FOREGROUND, config.getTextColor());
        attributed.addAttribute(TextAttribute.KERNING, TextAttribute.KERNING_ON);
        attributed.addAttribute(
                TextAttribute.WEIGHT,
                config.isBold() ? TextAttribute.WEIGHT_BOLD : TextAttribute.WEIGHT_REGULAR);
        attributed.addAttribute(
                TextAttribute.POSTURE,
                config.isItalic() ? TextAttribute.POSTURE_OBLIQUE : TextAttribute.POSTURE_REGULAR);
        if (config.isUnderline()) {
            attributed.addAttribute(TextAttribute.UNDERLINE, TextAttribute.UNDERLINE_ON);
        }
        return attributed;
    }
}
