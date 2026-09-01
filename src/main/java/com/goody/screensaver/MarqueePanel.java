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
 * Double-buffered panel that paints a seamless horizontal text marquee with
 * {@link Graphics2D}. Animation is driven by {@link Timer} at ~60 FPS, with
 * movement based on elapsed time so speed stays steady if a tick is delayed.
 */
public final class MarqueePanel extends JPanel {

    static final int TARGET_FPS = 60;
    private static final int FRAME_DELAY_MS = Math.round(1000f / TARGET_FPS);

    private final ScreensaverConfig config;
    private final Timer timer;

    private double x;
    private float stride = 1f;
    private long lastNanos;
    private boolean startedFromRight;

    public MarqueePanel(ScreensaverConfig config) {
        this.config = config;
        setOpaque(true);
        setDoubleBuffered(true);
        setBackground(config.getBackgroundColor());
        setFocusable(true);

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

    @Override
    public void addNotify() {
        super.addNotify();
        start();
    }

    @Override
    public void removeNotify() {
        stop();
        super.removeNotify();
    }

    private void onFrame() {
        long now = System.nanoTime();
        if (lastNanos == 0L) {
            lastNanos = now;
            repaint();
            return;
        }

        double elapsedSeconds = (now - lastNanos) / 1_000_000_000.0;
        lastNanos = now;
        elapsedSeconds = Math.min(elapsedSeconds, 0.05);

        x -= config.getPixelsPerSecond() * elapsedSeconds;
        while (stride > 0f && x < -stride) {
            x += stride;
        }
        repaint();
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
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
            FontRenderContext frc = g2.getFontRenderContext();
            TextLayout layout = new TextLayout(iterator, frc);

            float textWidth = layout.getAdvance();
            float gap = Math.max(96f, layout.getAscent() * 2.5f);
            stride = Math.max(1f, textWidth + gap);

            if (!startedFromRight) {
                x = getWidth();
                startedFromRight = true;
            }

            float baselineY = (getHeight() + layout.getAscent() - layout.getDescent()) / 2f;
            float drawX = (float) x;
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
     */
    private AttributedString createAttributedText() {
        String text = config.getMessage();
        if (text == null || text.isBlank()) {
            text = " ";
        }

        // Use FAMILY/SIZE instead of FONT. If FONT is set, Java ignores WEIGHT and
        // POSTURE, which is why underline (a separate decoration) still worked.
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
