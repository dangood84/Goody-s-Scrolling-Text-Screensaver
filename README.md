# Goody's Scrolling Text Screensaver

A full-screen Java Swing marquee: smooth horizontally scrolling text on an undecorated window, rendered with double-buffered `JPanel` and `Graphics2D` at about 60 FPS.

Preferences (message, colors, font, size, bold/italic/underline, speed) are saved with `java.util.prefs.Preferences` and restored on the next launch.

## Requirements

- **Java 21** or later (`java` and `javac` on your `PATH`)

## Run

From the project root:

```bash
./run.sh
```

That compiles to `out/` and opens the settings dialog. You can also pass flags through:

```bash
./run.sh --config
./run.sh --fullscreen
./run.sh /s
```

Or with Make:

```bash
make config        # settings dialog
make screensaver   # full-screen saver
make clean         # remove compiled classes
```

Manual compile and run:

```bash
javac --release 21 -encoding UTF-8 -d out src/main/java/com/goody/screensaver/*.java
java -cp out com.goody.screensaver.MarqueeSaver --config
```

## Command-line flags

| Flag | Action |
|------|--------|
| *(none)*, `/c`, `--config` | Open the preferences dialog |
| `/s`, `--fullscreen` | Start the full-screen screensaver immediately |
| `/p`, `--preview` | No-op (Windows-style preview hook); exits without a window |

Settings from the dialog are persisted, so `--fullscreen` uses the last saved look.

## Using it

1. Set the marquee text, font family (from `GraphicsEnvironment`), size, colors (`JColorChooser`), and bold / italic / underline.
2. Click **Start screensaver** (or launch with `--fullscreen`).
3. Press any key, or move the mouse more than a few pixels, to exit.

The live preview in the dialog uses the same scrolling renderer as full screen.

## OS-native ports

This Java app is the cross-platform reference. Native installers live in **sibling folders** (not inside this repo):

| Folder | Target | What it actually installs |
|--------|--------|---------------------------|
| `../GoodysScrollingTextScreensaverV2Mac` | macOS Sonoma+ | Native `.saver` for System Settings (no paid Apple Developer ID needed on the Mac that builds it) |
| `../GoodysScrollingTextScreensaverV2Win` | Windows 10+ | `.scr` launcher around the Java jar |
| `../GoodysScrollingTextScreensaverV2Lin` | Linux / Raspberry Pi OS | `.desktop` + xscreensaver hook around the Java jar |

A JAR cannot appear in macOS System Settings or as a Windows `.scr` by itself. See each folder’s README.

## Project layout

```
src/main/java/com/goody/screensaver/
  MarqueeSaver.java       # main, flag parsing
  SettingsDialog.java     # JDialog preferences UI
  MarqueeFrame.java       # full-screen window and wake-on-input
  MarqueePanel.java       # Timer + Graphics2D / AttributedString marquee
  ScreensaverConfig.java  # settings + Preferences load/save
```
