# Execution flow trace

A step-by-step trace from `main` through screen initialisation, timer startup, and how one frame is calculated and drawn.

Two threads matter:

- the **main thread** (starts the JVM, then mostly idle)
- the **Swing Event Dispatch Thread (EDT)** (windows, timer, painting)

Assume `--fullscreen` (or `/s`). With no args, steps 1–4 are the same, then you get `SettingsDialog` instead of `MarqueeFrame`; the preview at the bottom of that dialog still uses the same `MarqueePanel` loop from step 7 onward.

---

## Phase A — Process start (main thread)

**1.** The JVM loads `MarqueeSaver` and calls `main`.

```java
// MarqueeSaver.java
public static void main(String[] args) {
    LaunchMode mode = LaunchMode.fromArgs(args);
    // Windows Settings calls /p with a preview HWND. This Java UI cannot
    // parent into that native window, so we exit rather than flash a dialog.
    if (mode == LaunchMode.PREVIEW) {
        return;
    }

    // Swing components are not thread-safe. invokeLater posts this work onto
    // the EDT so window creation, painting, and later Timer ticks share one
    // thread. main() itself returns; the AWT thread keeps the JVM alive.
    SwingUtilities.invokeLater(() -> {
        installLookAndFeel();
        ScreensaverConfig config = ScreensaverConfig.load();
        switch (mode) {
            case FULLSCREEN -> new MarqueeFrame(config, () -> System.exit(0)).showFullScreen();
            case CONFIG, PREVIEW -> new SettingsDialog(config).setVisible(true);
        }
    });
}
```

**2.** `LaunchMode.fromArgs` walks `args`. Prefixes `/`, `-`, `--` are stripped. Last matching flag wins (`/s` → `FULLSCREEN`). `/p` returns from `main` immediately (no window).

**3.** `SwingUtilities.invokeLater` does **not** build the UI yet. It queues a `Runnable` on the EDT. `main` then returns. The JVM stays alive because AWT has started a non-daemon thread.

---

## Phase B — Screen initialisation (EDT)

**4.** The EDT runs the queued `Runnable`.

**5.** `UIManager.setLookAndFeel(...)` switches widget chrome to the OS theme.

**6.** `ScreensaverConfig.load()` creates a config object with field defaults, then overlays `java.util.prefs.Preferences` (message, font, colours, speed). This object is **appearance/speed only**. It does not hold `x`.

**7.** `new MarqueeFrame(config, () -> System.exit(0))`:

- Resolves the default `GraphicsDevice`.
- Makes an undecorated, always-on-top `JFrame` with a hidden cursor.
- `new MarqueePanel(config)`:
  - Stores a **reference** to that same config (later paints read it live).
  - `setOpaque(true)` / `setDoubleBuffered(true)` so each frame is drawn off-screen then blitted.
  - Builds a `javax.swing.Timer` with delay `round(1000/60) ≈ 17 ms`, listener `onFrame`, `coalesce` + `repeats`.
  - **Does not start the timer yet.** `x = 0`, `lastNanos = 0`, `startedFromRight = false`.
- `setContentPane(panel)` so the panel fills the frame.
- Key and mouse-motion listeners are attached to **both** frame and panel.

**8.** `showFullScreen()`:

- If exclusive full screen is supported: `device.setFullScreenWindow(this)` (shows only this window, typically hides dock/taskbar).
- Else: maximize and `setVisible(true)`.
- `toFront` / `requestFocus` so key events have somewhere to go.

**9.** Showing the window **realizes** the component tree (native peers). AWT calls `MarqueePanel.addNotify()`.

```java
// MarqueePanel.java
@Override
public void addNotify() {
    super.addNotify();
    start();
}
```

**10.** `start()` sets `lastNanos = 0` and `timer.start()`. Swing schedules the first `ActionEvent` ~17 ms later **on the EDT**. The animation loop is now armed.

Swing will also schedule an initial paint of the panel (empty or first layout) independently of the timer.

---

## Phase C — Per-tick update (EDT, ~60 times per second)

**11.** The timer fires → `onFrame()`.

**12. First tick** (`lastNanos == 0`):

- `lastNanos = System.nanoTime()` so the next delta is “since last frame”, not “since JVM start”.
- `repaint()`; **no movement**. Return.

**13. Later ticks:**

```text
now            = System.nanoTime()
elapsedSeconds = min((now - lastNanos) / 1e9, 0.05)   // cap 50 ms
lastNanos      = now

x = x - pixelsPerSecond * elapsedSeconds    // e.g. 140 * 0.0167 ≈ 2.3 px left

while (x < -stride)
    x = x + stride                          // recycle so x does not run to −∞
```

`stride` was last set during paint (text width + gap). Until the first real paint, it is `1`.

The corresponding source:

```java
// MarqueePanel.java — onFrame()
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
```

**14.** `repaint()` does **not** draw. It marks the panel dirty. Swing coalesces dirty regions and later calls `paint` → `paintComponent` on this same EDT.

State after a tick: **`x` changed**; pixels on screen have not, until the draw pass.

---

## Phase D — Per-frame draw (EDT)

**15.** Swing calls `paintComponent(Graphics g)`.

**16.** `g.create()` clones the context so rendering hints cannot leak. Cast to `Graphics2D`.

**17.** Hints: antialias, text antialias, fractional metrics (so a `double x` can land on a sub-pixel glyph position).

**18.** Fill the panel with `config.getBackgroundColor()`.

**19.** Build an `AttributedString` from **current** config (message, family, size, weight, posture, underline, foreground). `TextLayout` measures and draws that string.

**20.** Metrics → tiling distance:

```text
textWidth = layout.getAdvance()
gap       = max(96, ascent * 2.5)
stride    = max(1, textWidth + gap)     // stored for the *next* onFrame wrap
```

**21.** First successful paint only: `x = getWidth()`, `startedFromRight = true` (enter from the right).

**22.** Vertical: `baselineY = (height + ascent - descent) / 2` (`TextLayout.draw` uses a **baseline**, not top-left).

**23.** Horizontal tiling:

```text
drawX = (float) x
while (drawX > 0)        drawX -= stride     // start off the left edge
while (drawX < width)
    layout.draw(g2, drawX, baselineY)
    drawX += stride
```

Several copies of the same layout are stamped so the band has no gap when `x` wraps.

**24.** `g2.dispose()`. Double-buffering blits the off-screen image to the screen.

---

## The repeating loop

```text
Timer (~17 ms, EDT)
  → onFrame:  x -= speed * dt; wrap; repaint()
  → Swing paint: paintComponent reads x + config, draws TextLayout(s)
  → wait for next timer event
```

Input (any key, or mouse move ≥ 12 px from the first sample) calls `exitScreensaver()`: `timer.stop()`, `setFullScreenWindow(null)`, `dispose()`, `System.exit(0)`.

---

## Settings path (same draw loop)

`--config` / no args: step 7 is `SettingsDialog` instead. Its preview is another `MarqueePanel` on the **same** config instance. Checkbox/slider `persist()` writes config + Preferences; the next `paintComponent` rereads config. **Start screensaver** copies config, disposes the dialog (without exiting), then jumps to `MarqueeFrame` at step 7.
