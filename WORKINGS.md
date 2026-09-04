# How this screensaver works

This note is for an automation tester who wants to see how a small Java Swing application is structured: where it starts, who owns state, who paints pixels, and how the marquee moves.

You do not need to be a Swing expert. The same ideas show up in many GUI and game-like apps: an entry point, a UI thread, a model, a view, and a timed loop that updates positions then redraws.

## Mental model

```
main()
  → parse flags
  → queue work on the Swing Event Dispatch Thread (EDT)
      → load ScreensaverConfig (state)
      → either SettingsDialog (settings UI)
         or MarqueeFrame (full-screen window)
              → MarqueePanel (timer + paint)
```

| Layer | Class | Tester-friendly analogy |
|-------|--------|-------------------------|
| Entry / routing | `MarqueeSaver` | Test runner that picks “config” vs “run” from CLI |
| State | `ScreensaverConfig` | Test data / fixture that is saved and reloaded |
| Settings UI | `SettingsDialog` | Form that writes into the fixture |
| Window shell | `MarqueeFrame` | Full-screen host + “abort on input” |
| Animation view | `MarqueePanel` | The thing that actually moves and draws |

Swing is **event-driven**. Almost everything after `main` runs on one thread: the **Event Dispatch Thread (EDT)**. Clicks, timer ticks, and `paintComponent` all happen there. That is why the animation uses `javax.swing.Timer` instead of a raw `while (true)` loop on a background thread.

---

## 1. Entry point and execution lifecycle

### Where `main` lives

The real entry point is `MarqueeSaver.main(String[] args)`.

`ScrollingTextScreensaver.main` only forwards to that method so an older class name still works.

`MarqueeSaver` has a private constructor. It is never instantiated. It is a **static bootstrap** class: parse arguments, then start the UI.

### Lifecycle, step by step

1. **JVM starts** and calls `main`.
2. **`LaunchMode.fromArgs(args)`** maps Windows-style and GNU-style flags:
   - `/s` or `--fullscreen` → full-screen saver
   - `/c` or `--config` (also the default with no args) → settings dialog
   - `/p` or `--preview` → exit immediately (Settings preview pane; this Java UI does not embed into a native HWND)
3. If the mode is `PREVIEW`, `main` returns. The process ends. No window.
4. Otherwise **`SwingUtilities.invokeLater(...)`** posts a Runnable to the EDT.  
   `main` itself must not create Swing windows. They are not thread-safe.
5. On the EDT:
   - install the system look and feel
   - **`ScreensaverConfig.load()`** reads last-saved preferences (or defaults)
   - `switch` on mode:
     - `FULLSCREEN` → `new MarqueeFrame(config, () -> System.exit(0)).showFullScreen()`
     - `CONFIG` → `new SettingsDialog(config).setVisible(true)`
6. After the window is showing, the process stays alive because Swing keeps a **non-daemon AWT thread** running until `System.exit(0)` or the last window is disposed (settings close also calls `System.exit(0)`).

### Two user journeys

**Settings first** (`./run.sh` or `--config`):

```
EDT creates SettingsDialog
  → user edits controls → each change writes ScreensaverConfig and save()
  → live MarqueePanel in the dialog already animates (same renderer as full screen)
  → Close → windowClosed → save → System.exit(0)
  → Start screensaver → save, hide/dispose dialog, MarqueeFrame full screen
       → any key or ~12px mouse move → stop timer, leave exclusive full screen, System.exit(0)
```

**Saver first** (`--fullscreen` / `/s`):

```
EDT creates MarqueeFrame immediately
  → exclusive full screen if the graphics device supports it
  → MarqueePanel timer starts when the panel is realized (addNotify)
  → input → System.exit(0)
```

### Why testers care

- **CLI is the feature flag.** Automating “open settings” vs “open saver” is `java ... MarqueeSaver --config` vs `--fullscreen`.
- **Preferences survive process restarts.** A test that changes the message and relaunches should see the same text. Storage is `java.util.prefs.Preferences` (OS user prefs, not a file in the repo).
- **Exit is process-level** on the full-screen path (`System.exit(0)`), not “navigate back to a page.”

---

## 2. Main classes and responsibilities

This is a **separation of UI vs state**, not a full MVC framework. There is no database and no service layer.

### `MarqueeSaver` — composition root

- Parses argv
- Chooses which window to open
- Does **not** draw or store marquee coordinates

### `ScreensaverConfig` — state management

Holds the marquee *appearance and speed*, not the current `x` position:

- message, font family, font size
- bold, italic, underline
- text colour, background colour
- pixels per second

**Load / save:** `Preferences.userNodeForPackage(ScreensaverConfig.class)`. Keys are string names like `"message"` and `"fontSize"`. Colours are stored as `Color.getRGB()` ints.

**Copy:** `copy()` snapshots state when launching full screen so the saver is not coupled to the dialog’s later edits (the dialog is disposed anyway).

**Clamping:** setters use `Math.clamp` so sliders cannot push illegal sizes/speeds.

This class is the closest thing to a **model**. It has no Swing widgets.

### `SettingsDialog` — settings UI

- A `JDialog` with form controls (text field, combo box, sliders, checkboxes, `JColorChooser`)
- Writes into the **same** `ScreensaverConfig` instance it was given
- `persist(Runnable)` = mutate config + `config.save()` on each change
- Hosts a small `MarqueePanel` as a **live preview** (same class as full screen)
- Does not compute marquee `x`; it only changes config fields the panel reads while painting

### `MarqueeFrame` — window chrome and wake-on-input

- Undecorated `JFrame`, hidden cursor, always on top
- Puts a `MarqueePanel` in `setContentPane`
- `showFullScreen()` uses `GraphicsDevice.setFullScreenWindow` when supported, otherwise maximized
- **Does not paint the text.** It listens for keys and mouse motion and then shuts down
- Mouse “wake”: first motion is recorded; later motion of 12 pixels or more exits (so the initial cursor warp does not instantly quit)

### `MarqueePanel` — rendering + animation loop

- Double-buffered `JPanel`
- Owns **transient animation state**: `x`, `stride`, `lastNanos`, `startedFromRight`
- Owns the `javax.swing.Timer`
- `paintComponent` is the only place `Graphics2D` draws the marquee
- Reads `ScreensaverConfig` every paint, so the preview updates when checkboxes change

### `ScrollingTextScreensaver`

Compatibility alias for `main`. No extra behaviour.

### What is *not* a class

There is no separate `Marquee` sprite object. Position is a `double x` on the panel. The “object” on screen is a `TextLayout` rebuilt from an `AttributedString` each frame.

**Bold/italic vs underline:** styles go on the `AttributedString` via `TextAttribute`. Do not also set `TextAttribute.FONT` to a `PLAIN` `Font`, or Java ignores `WEIGHT` and `POSTURE`. Underline is a decoration, so it still applied when that bug existed.

---

## 3. How the render / animation loop works

This is **not** a classic game loop on a worker thread (`while (running) { update(); render(); }`).

It is a **Swing timer loop** on the EDT:

```
javax.swing.Timer (~16 ms, 60 FPS)
    → onFrame()           // update position
        → repaint()       // ask Swing to paint later
            → paintComponent()  // draw using current x
```

### Timer setup

```text
TARGET_FPS = 60
FRAME_DELAY_MS = round(1000 / 60)  → 17 ms
timer = new Timer(FRAME_DELAY_MS, event -> onFrame())
timer.setCoalesce(true)   // if the EDT is busy, collapse pending ticks
timer.setRepeats(true)
```

`Timer` fires `ActionEvent`s on the **EDT**, so `onFrame` may touch Swing safely.

### When it starts and stops

| Hook | Meaning |
|------|--------|
| `addNotify()` | Panel is attached to a realized window → `start()` |
| `removeNotify()` | Panel is taken off screen → `stop()` |
| `MarqueeFrame.exitScreensaver()` | Also calls `panel.stop()` before dispose |

The settings preview starts automatically when the dialog is shown, for the same reason: the preview `MarqueePanel` gets `addNotify`.

### `repaint()` vs `paintComponent`

- `repaint()` does **not** draw immediately. It marks the component dirty. Swing later calls `paint` → `paintComponent`.
- All drawing is in `paintComponent(Graphics)`.
- The panel is `setDoubleBuffered(true)` so Swing paints to an off-screen buffer then blits it, which reduces flicker.

### Why not `Thread.sleep` in a loop?

A blocking loop on the EDT would freeze the UI (no paints, no key events). A loop on another thread would have to call `SwingUtilities.invokeLater` to paint, which is easy to get wrong. The timer is the Swing-native “game loop.”

### Frame timing vs movement

The timer *aims* at 60 Hz, but it can jitter. Movement is **not** “subtract 2 pixels every tick.” It uses **elapsed real time** so speed stays close to `pixelsPerSecond` even if a tick is late (see next section).

---

## 4. Position math on each tick

Two coordinates matter:

- **`x`** — horizontal origin of the “primary” copy of the text (pixels, `double` for sub-pixel accumulation)
- **`baselineY`** — vertical position of the text baseline (recomputed every paint from panel height and font metrics)

Direction: **right to left** (classic marquee). `x` decreases over time.

### Horizontal speed (in `onFrame`)

```text
now = System.nanoTime()
elapsedSeconds = (now - lastNanos) / 1_000_000_000
elapsedSeconds = min(elapsedSeconds, 0.05)     // cap so a pause does not jump

x = x - pixelsPerSecond * elapsedSeconds
```

Example: 140 px/s and a 16.7 ms frame → about `140 * 0.0167 ≈ 2.3` pixels left that frame.

The `0.05` cap (50 ms) stops a huge leap if the app was stalled (breakpoint, sleep, window not shown).

First tick only stores `lastNanos` and `repaint()`s; it does not move, so the first delta is not “from JVM start.”

### Wrap-around so `x` does not run to −∞

`stride` is the distance from the start of one text copy to the next:

```text
textWidth = layout.getAdvance()           // width of the attributed string
gap       = max(96, ascent * 2.5)         // space between repeats
stride    = max(1, textWidth + gap)
```

After moving:

```text
while (stride > 0 && x < -stride)
    x = x + stride
```

So `x` stays in a bounded range. Visually the band is endless; mathematically one copy is recycled.

### First appearance (in `paintComponent`)

Until the first successful paint:

```text
if (!startedFromRight)
    x = getWidth()          // start just off the right edge
    startedFromRight = true
```

The message enters from the right instead of popping in at `x = 0`.

### Drawing many copies (seamless band)

One `TextLayout` is drawn in a loop:

```text
drawX = (float) x
while (drawX > 0)
    drawX -= stride          // walk left until we start off-screen

while (drawX < panelWidth)
    layout.draw(g2, drawX, baselineY)
    drawX += stride          // stamp copies across the screen
```

That is why you always see text: copies are tiled every `stride` pixels. `x` only shifts the whole tiling.

### Vertical centering

```text
baselineY = (panelHeight + ascent - descent) / 2
```

`TextLayout` draws on a **baseline**, not a top-left box. Ascent is above the baseline, descent below. That formula puts the ink roughly in the vertical middle.

### Who updates what

| Value | Updated when | Role |
|-------|----------------|------|
| `x` | every timer tick | animation state |
| `stride` | every paint (depends on font/text) | wrap distance |
| `baselineY` | every paint | vertical layout |
| config fields | settings controls | speed and look; **not** `x` |

Speed tests: change the Speed slider (`pixelsPerSecond`) and the same `x -= speed * dt` formula makes the band move faster. You are not changing a “step size” constant; you are changing the velocity term.

---

## Quick map of files

```
src/main/java/com/goody/screensaver/
  MarqueeSaver.java              # main, flags, EDT bootstrap
  ScrollingTextScreensaver.java  # main alias
  ScreensaverConfig.java         # model + Preferences
  SettingsDialog.java            # settings view
  MarqueeFrame.java              # full-screen shell + input
  MarqueePanel.java              # timer, x math, Graphics2D
```

If you are tracing in a debugger, put breakpoints on `MarqueeSaver.main`, `MarqueePanel.onFrame`, and `MarqueePanel.paintComponent`. You will see: **tick updates `x` → `repaint` → paint reads `x` and config → draw**.
