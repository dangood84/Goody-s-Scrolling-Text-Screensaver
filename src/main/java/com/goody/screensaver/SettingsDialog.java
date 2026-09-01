package com.goody.screensaver;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.event.ItemEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JColorChooser;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JTextField;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/**
 * Configuration dialog for marquee text, colors, font family/size, and styles.
 * Changes are written to {@link java.util.prefs.Preferences} as they happen.
 */
public final class SettingsDialog extends JDialog {

    private final ScreensaverConfig config;
    private boolean launchingScreensaver;

    public SettingsDialog(ScreensaverConfig config) {
        super((Frame) null, "Goody's Scrolling Text Screensaver", false);
        this.config = config;

        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(640, 560));

        var root = new JPanel(new BorderLayout(0, 12));
        root.setBorder(BorderFactory.createEmptyBorder(16, 18, 16, 18));
        root.add(buildHeader(), BorderLayout.NORTH);
        root.add(buildForm(), BorderLayout.CENTER);
        root.add(buildPreviewAndActions(), BorderLayout.SOUTH);
        setContentPane(root);
        pack();
        setLocationRelativeTo(null);

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent event) {
                if (!launchingScreensaver) {
                    config.save();
                    System.exit(0);
                }
            }
        });
    }

    private JPanel buildHeader() {
        var header = new JPanel(new BorderLayout());
        var title = new JLabel("Preferences & Settings");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 20f));
        var subtitle = new JLabel("Saved automatically  •  /c config  •  /s fullscreen  •  Java 21");
        subtitle.setForeground(new Color(90, 90, 90));
        header.add(title, BorderLayout.NORTH);
        header.add(subtitle, BorderLayout.SOUTH);
        return header;
    }

    private JPanel buildForm() {
        var form = new JPanel(new GridBagLayout());
        var constraints = new GridBagConstraints();
        constraints.insets = new Insets(6, 4, 6, 4);
        constraints.anchor = GridBagConstraints.WEST;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.weightx = 1;

        var messageField = new JTextField(config.getMessage(), 32);
        messageField.getDocument().addDocumentListener(onTextChange(() -> persist(() -> config.setMessage(messageField.getText()))));
        addRow(form, constraints, 0, "Message", messageField);

        var fontBox = new JComboBox<>(ScreensaverConfig.availableFontFamilies());
        fontBox.setSelectedItem(config.getFontFamily());
        fontBox.addItemListener(event -> {
            if (event.getStateChange() == ItemEvent.SELECTED && fontBox.getSelectedItem() instanceof String family) {
                persist(() -> config.setFontFamily(family));
            }
        });
        addRow(form, constraints, 1, "Font family", fontBox);

        var sizeSlider = new JSlider(8, 300, config.getFontSize());
        sizeSlider.setMajorTickSpacing(48);
        sizeSlider.setMinorTickSpacing(8);
        sizeSlider.setPaintTicks(true);
        var sizeValue = new JLabel(sizeLabel(config.getFontSize()), JLabel.RIGHT);
        sizeValue.setPreferredSize(new Dimension(64, 16));
        sizeSlider.addChangeListener(event -> persist(() -> {
            config.setFontSize(sizeSlider.getValue());
            sizeValue.setText(sizeLabel(sizeSlider.getValue()));
        }));
        var sizeRow = new JPanel(new BorderLayout(8, 0));
        sizeRow.add(sizeSlider, BorderLayout.CENTER);
        sizeRow.add(sizeValue, BorderLayout.EAST);
        addRow(form, constraints, 2, "Font size", sizeRow);

        var boldBox = new JCheckBox("Bold", config.isBold());
        var italicBox = new JCheckBox("Italic", config.isItalic());
        var underlineBox = new JCheckBox("Underline", config.isUnderline());
        boldBox.addItemListener(event -> persist(() -> config.setBold(boldBox.isSelected())));
        italicBox.addItemListener(event -> persist(() -> config.setItalic(italicBox.isSelected())));
        underlineBox.addItemListener(event -> persist(() -> config.setUnderline(underlineBox.isSelected())));
        var styleRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        styleRow.add(boldBox);
        styleRow.add(italicBox);
        styleRow.add(underlineBox);
        addRow(form, constraints, 3, "Style", styleRow);

        addRow(form, constraints, 4, "Text color", colorRow(config.getTextColor(), color -> persist(() -> config.setTextColor(color))));
        addRow(form, constraints, 5, "Background", colorRow(config.getBackgroundColor(), color -> persist(() -> config.setBackgroundColor(color))));

        var speedSlider = new JSlider(10, 400, config.getPixelsPerSecond());
        speedSlider.setMajorTickSpacing(100);
        speedSlider.setMinorTickSpacing(20);
        speedSlider.setPaintTicks(true);
        var speedValue = new JLabel(speedLabel(config.getPixelsPerSecond()), JLabel.RIGHT);
        speedValue.setPreferredSize(new Dimension(88, 16));
        speedSlider.addChangeListener(event -> persist(() -> {
            config.setPixelsPerSecond(speedSlider.getValue());
            speedValue.setText(speedLabel(speedSlider.getValue()));
        }));
        var speedRow = new JPanel(new BorderLayout(8, 0));
        speedRow.add(speedSlider, BorderLayout.CENTER);
        speedRow.add(speedValue, BorderLayout.EAST);
        addRow(form, constraints, 6, "Speed", speedRow);

        return form;
    }

    private JPanel buildPreviewAndActions() {
        var south = new JPanel(new BorderLayout(0, 10));

        var preview = new MarqueePanel(config);
        preview.setPreferredSize(new Dimension(600, 110));
        preview.setBorder(BorderFactory.createLineBorder(new Color(40, 40, 40)));
        south.add(preview, BorderLayout.CENTER);

        var buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        var close = new JButton("Close");
        var start = new JButton("Start screensaver");
        start.addActionListener(event -> launchScreensaver());
        close.addActionListener(event -> dispose());
        getRootPane().setDefaultButton(start);
        buttons.add(close);
        buttons.add(start);
        south.add(buttons, BorderLayout.SOUTH);

        var hint = new JLabel("Any key or a significant mouse move exits the full-screen saver.");
        hint.setForeground(new Color(90, 90, 90));
        south.add(hint, BorderLayout.NORTH);
        return south;
    }

    private void launchScreensaver() {
        config.save();
        launchingScreensaver = true;
        setVisible(false);
        dispose();
        new MarqueeFrame(config.copy(), () -> System.exit(0)).showFullScreen();
    }

    private void persist(Runnable update) {
        update.run();
        config.save();
    }

    private JPanel colorRow(Color initial, Consumer<Color> onChoose) {
        var row = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        var swatch = new JPanel();
        swatch.setPreferredSize(new Dimension(36, 22));
        swatch.setBackground(initial);
        swatch.setBorder(BorderFactory.createLineBorder(Color.DARK_GRAY));
        var choose = new JButton("Choose...");
        choose.addActionListener(event -> {
            Color chosen = JColorChooser.showDialog(this, "Choose color", swatch.getBackground());
            if (chosen != null) {
                swatch.setBackground(chosen);
                onChoose.accept(chosen);
            }
        });
        row.add(swatch);
        row.add(choose);
        return row;
    }

    private static void addRow(JPanel form, GridBagConstraints constraints, int row, String label, Component field) {
        constraints.gridy = row;
        constraints.gridx = 0;
        constraints.weightx = 0;
        constraints.fill = GridBagConstraints.NONE;
        form.add(new JLabel(label), constraints);
        constraints.gridx = 1;
        constraints.weightx = 1;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        form.add(field, constraints);
    }

    private static String sizeLabel(int points) {
        return points + " pt";
    }

    private static String speedLabel(int pixelsPerSecond) {
        return pixelsPerSecond + " px/s";
    }

    private static DocumentListener onTextChange(Runnable action) {
        return new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                action.run();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                action.run();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                action.run();
            }
        };
    }
}
