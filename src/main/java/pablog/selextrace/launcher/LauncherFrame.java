package pablog.selextrace.launcher;

import com.formdev.flatlaf.FlatClientProperties;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;
import pablog.selextrace.launcher.PrerequisiteChecker.CheckResult;
import java.util.concurrent.atomic.AtomicBoolean;
import com.github.weisj.jsvg.SVGDocument;
import com.github.weisj.jsvg.parser.SVGLoader;
import com.github.weisj.jsvg.view.FloatSize;
import java.net.URL;

public final class LauncherFrame extends JFrame {
    private final LauncherConfigStore configStore;
    private LauncherConfig config;
    private final PrerequisiteChecker prerequisiteChecker = new PrerequisiteChecker();
    private final ArtifactManager artifactManager = new ArtifactManager();
    private final ServiceManager serviceManager = new ServiceManager();
    private final ServiceManager.ServiceHandle serviceHandle = serviceManager.newHandle();

    private final JLabel headerTitle = new JLabel("SELEXTrace Launcher");
    private final JLabel headerSubtitle = new JLabel("Prerequisites - Step 1 of 4");
    private final JButton themeToggleButton = new JButton("☀️");
    private final JPanel cardPanel = new JPanel(new CardLayout());
    private final JLabel consoleStatusLabel = new JLabel("Ready");
    private final JTextArea consoleArea = new JTextArea();

    private final JButton backButton = new JButton("Back");
    private final JButton nextButton = new JButton("Next");

    private final AtomicBoolean servicesRunning = new AtomicBoolean(false);

    private final PrerequisitesPanel prerequisitesPanel = new PrerequisitesPanel();
    private final DownloadPanel downloadPanel = new DownloadPanel();
    private final ConfigurationPanel configurationPanel = new ConfigurationPanel();
    private final ServicesPanel servicesPanel = new ServicesPanel();

    private int stepIndex = 0;
    private ArtifactManager.ArtifactBundle artifactBundle;
    private TrayIcon trayIcon;
    private boolean traySupported;

    private static java.util.List<String> detectLocalAddresses() {
        java.util.List<String> addresses = new java.util.ArrayList<>();
        addresses.add("localhost");
        try {
            java.util.Enumeration<java.net.NetworkInterface> interfaces = java.net.NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                java.net.NetworkInterface iface = interfaces.nextElement();
                if (!iface.isUp() || iface.isLoopback() || iface.isVirtual()) {
                    continue;
                }
                java.util.Enumeration<java.net.InetAddress> addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    java.net.InetAddress addr = addrs.nextElement();
                    if (addr instanceof java.net.Inet4Address && !addr.isLoopbackAddress()) {
                        addresses.add(addr.getHostAddress());
                    }
                }
            }
        } catch (java.net.SocketException ignored) {
        }
        return addresses;
    }

    public LauncherFrame(LauncherConfigStore configStore, LauncherConfig config) {
        super("SELEXTrace Launcher");
        this.configStore = configStore;
        this.config = config;

        ThemeManager.apply(config.darkTheme());
        initUi();
        SwingUtilities.updateComponentTreeUI(this);
        installTray();
        installCloseBehavior();
        setIconImage(createAppIcon());
        pack();
        setLocationRelativeTo(null);
        setMinimumSize(new Dimension(1180, 780));
        setSize(1280, 860);
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);

        SwingUtilities.invokeLater(() -> {
            prerequisitesPanel.refresh();
            setStep(0);
        });
    }

    private void initUi() {
        setLayout(new BorderLayout(12, 12));

        JPanel header = new JPanel(new BorderLayout(16, 0));
        header.setBorder(new EmptyBorder(14, 16, 12, 16));
        header.add(buildHeaderBlock(), BorderLayout.WEST);

        themeToggleButton.setFocusable(false);
        themeToggleButton.addActionListener(e -> toggleTheme());
        themeToggleButton.putClientProperty(FlatClientProperties.BUTTON_TYPE, FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
        themeToggleButton.setText(config.darkTheme() ? "☀️" : "🌙");

        JPanel headerRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        headerRight.setOpaque(false);
        headerRight.add(themeToggleButton);
        header.add(headerRight, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);

        cardPanel.setBorder(new EmptyBorder(0, 16, 0, 16));
        cardPanel.add(prerequisitesPanel, "step0");
        cardPanel.add(downloadPanel, "step1");
        cardPanel.add(configurationPanel, "step2");
        cardPanel.add(servicesPanel, "step3");
        add(cardPanel, BorderLayout.CENTER);

        JPanel consolePanel = new JPanel(new BorderLayout(8, 8));
        consolePanel.setBorder(new EmptyBorder(0, 16, 12, 16));

        JLabel consoleTitle = new JLabel("Console");
        consoleTitle.putClientProperty(FlatClientProperties.STYLE, "font: bold $h3.font");
        consolePanel.add(consoleTitle, BorderLayout.NORTH);

        consoleArea.setEditable(false);
        consoleArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        consoleArea.setRows(8);
        consoleArea.setLineWrap(true);
        consoleArea.setWrapStyleWord(true);
        consoleArea.setBorder(new EmptyBorder(8, 8, 8, 8));
        JScrollPane consoleScroll = new JScrollPane(consoleArea);
        consoleScroll.putClientProperty(FlatClientProperties.STYLE, "arc: 18");
        consolePanel.add(consoleScroll, BorderLayout.CENTER);

        consoleStatusLabel.putClientProperty(FlatClientProperties.STYLE, "font: bold $semibold.font");
        consolePanel.add(consoleStatusLabel, BorderLayout.SOUTH);
        add(consolePanel, BorderLayout.SOUTH);

        JPanel footer = new JPanel(new BorderLayout());
        footer.setBorder(new EmptyBorder(0, 16, 16, 16));
        JPanel nav = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        nav.setOpaque(false);

        backButton.addActionListener(e -> goBack());
        nextButton.addActionListener(e -> goNext());
        nav.add(backButton);
        nav.add(nextButton);
        footer.add(nav, BorderLayout.EAST);
        add(footer, BorderLayout.PAGE_END);
    }

    private JComponent buildHeaderBlock() {
        JPanel block = new JPanel();
        block.setOpaque(false);
        block.setLayout(new BoxLayout(block, BoxLayout.Y_AXIS));

        headerTitle.putClientProperty(FlatClientProperties.STYLE, "font: bold $h1.font");
        headerSubtitle.putClientProperty(FlatClientProperties.STYLE, "font: semibold $h3.font; foreground: $Label.disabledForeground");
        block.add(headerTitle);
        block.add(Box.createVerticalStrut(2));
        block.add(headerSubtitle);

        return block;
    }

    private void installTray() {
        traySupported = SystemTray.isSupported();
        if (!traySupported) {
            return;
        }
        PopupMenu popupMenu = new PopupMenu();
        MenuItem openItem = new MenuItem("Open SELEXTrace Launcher");
        MenuItem exitItem = new MenuItem("Exit");
        popupMenu.add(openItem);
        popupMenu.addSeparator();
        popupMenu.add(exitItem);

        trayIcon = new TrayIcon(createTrayImage(), "SELEXTrace Launcher", popupMenu);
        trayIcon.setImageAutoSize(true);
        trayIcon.addActionListener(e -> restoreFromTray());

        openItem.addActionListener(e -> restoreFromTray());
        exitItem.addActionListener(e -> exitApplication());

        try {
            SystemTray.getSystemTray().add(trayIcon);
            trayIcon.displayMessage("SELEXTrace Launcher", "Launcher is running in the tray.", TrayIcon.MessageType.INFO);
        } catch (AWTException ignored) {
        }
    }

    private void installCloseBehavior() {
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                handleCloseRequest();
            }
        });
    }

    private void handleCloseRequest() {
        if (servicesRunning.get()) {
            String[] options = {"Minimize to tray", "Stop services and exit", "Cancel"};
            int choice = JOptionPane.showOptionDialog(
                    this,
                    "Services are running. What would you like to do?",
                    "SELEXTrace Launcher",
                    JOptionPane.DEFAULT_OPTION,
                    JOptionPane.QUESTION_MESSAGE,
                    null,
                    options,
                    options[0]
            );
            if (choice == 0) {
                minimizeToTray();
            } else if (choice == 1) {
                stopServicesAndExit();
            }
            return;
        }

        if (traySupported) {
            minimizeToTray();
        } else {
            dispose();
            System.exit(0);
        }
    }

    private void minimizeToTray() {
        setVisible(false);
        if (trayIcon != null) {
            trayIcon.displayMessage("SELEXTrace Launcher", "Application minimized to tray.", TrayIcon.MessageType.INFO);
        }
    }

    private void restoreFromTray() {
        setVisible(true);
        setExtendedState(JFrame.NORMAL);
        toFront();
        requestFocus();
    }

    private void exitApplication() {
        if (servicesRunning.get()) {
            int confirm = JOptionPane.showConfirmDialog(this, "Stop running services and exit?", "Exit SELEXTrace Launcher", JOptionPane.YES_NO_OPTION);
            if (confirm != JOptionPane.YES_OPTION) {
                return;
            }
        }
        stopServicesAndExit();
    }

    private void stopServicesAndExit() {
        servicesRunning.set(false);
        serviceManager.stopAll(serviceHandle, snap -> appendStatus(snap.details()));
        dispose();
        if (trayIcon != null) {
            SystemTray.getSystemTray().remove(trayIcon);
        }
        System.exit(0);
    }

    private void toggleTheme() {
        config = new LauncherConfig(!config.darkTheme(), config.databaseName(), config.databaseUsername(), config.databasePassword(),
                config.postgresqlMajorVersion(), config.backendPort(), config.frontendPort(), config.googleClientId(), config.googleClientSecret(),
                config.bindAddress());
        ThemeManager.apply(config.darkTheme());
        themeToggleButton.setText(config.darkTheme() ? "☀️" : "🌙");
        SwingUtilities.updateComponentTreeUI(this);
        themeToggleButton.setText(config.darkTheme() ? "☀️" : "🌙");
    }

    private void setStep(int index) {
        this.stepIndex = Math.max(0, Math.min(3, index));
        CardLayout layout = (CardLayout) cardPanel.getLayout();
        layout.show(cardPanel, "step" + stepIndex);

        headerSubtitle.setText(switch (stepIndex) {
            case 0 -> "Step 1 of 4 - Prerequisites";
            case 1 -> "Step 2 of 4 - Artifact Downloading";
            case 2 -> "Step 3 of 4 - Service Configuration";
            case 3 -> "Step 4 of 4 - Running Services";
            default -> "SELEXTrace Launcher";
        });

        backButton.setEnabled(stepIndex > 0);
        nextButton.setVisible(stepIndex < 3);

        if (stepIndex == 1) {
            downloadPanel.onEnter();
        } else if (stepIndex == 2) {
            configurationPanel.loadFromConfig(config);
        } else if (stepIndex == 3) {
            servicesPanel.onEnter();
        }
    }

    private void goBack() {
        if (stepIndex > 0) {
            setStep(stepIndex - 1);
        }
    }

    private void goNext() {
        switch (stepIndex) {
            case 0 -> {
                if (!prerequisitesPanel.canProceed()) {
                    appendStatus("Resolve the prerequisite checks before continuing.");
                    return;
                }
                setStep(1);
            }
            case 1 -> {
                if (!downloadPanel.canProceed()) {
                    appendStatus("Wait for the artifact download to complete.");
                    return;
                }
                setStep(2);
            }
            case 2 -> {
                try {
                    config = configurationPanel.toConfig(config.darkTheme());
                    configStore.save(config);
                    appendStatus("Configuration saved to selextrace.cfg");
                    setStep(3);
                } catch (IllegalArgumentException ex) {
                    JOptionPane.showMessageDialog(this, ex.getMessage(), "Validation error", JOptionPane.ERROR_MESSAGE);
                } catch (IOException ex) {
                    JOptionPane.showMessageDialog(this, "Could not save configuration: " + ex.getMessage(), "Save error", JOptionPane.ERROR_MESSAGE);
                }
            }
            case 3 -> {
                setVisible(false);
            }
        }
    }

    private void appendStatus(String message) {
        SwingUtilities.invokeLater(() -> {
            consoleArea.append(message + System.lineSeparator());
            consoleArea.setCaretPosition(consoleArea.getDocument().getLength());
            consoleStatusLabel.setText(message);
        });
    }

    private BufferedImage createAppIcon() {
        URL url = LauncherFrame.class.getResource("/favicon.svg");
        if (url == null) {
            throw new IllegalStateException("Could not find icon resource /favicon.svg");
        }
        SVGLoader loader = new SVGLoader();
        SVGDocument document = loader.load(url);
        if (document == null) {
            throw new IllegalStateException("Failed to load /favicon.svg");
        }

        int size = 64;
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);

        FloatSize docSize = document.size();
        double scaleX = size / (double) docSize.width;
        double scaleY = size / (double) docSize.height;
        g.scale(scaleX, scaleY);

        document.render(null, g);
        g.dispose();
        return image;
    }

    private Image createTrayImage() {
        return createAppIcon();
    }

    private final class PrerequisitesPanel extends JPanel {
        private final JLabel javaLabel = new JLabel("Java SDK");
        private final JLabel dockerLabel = new JLabel("Docker");
        private final JLabel javaDetail = new JLabel("Pending");
        private final JLabel dockerDetail = new JLabel("Pending");
        private boolean pass;

        PrerequisitesPanel() {
            setBorder(new EmptyBorder(12, 0, 12, 0));
            setLayout(new GridBagLayout());
            GridBagConstraints c = new GridBagConstraints();
            c.insets = new Insets(10, 10, 10, 10);
            c.fill = GridBagConstraints.HORIZONTAL;
            c.weightx = 1;

            addCard(c, 0, "Java SDK Check", "Verifies Java 25 or higher.");
            addCard(c, 1, "Docker Environment Check", "Checks the Docker daemon, with WSL fallback on Windows.");

            JButton refresh = new JButton("Run checks");
            refresh.addActionListener(e -> refresh());
            c.gridx = 0;
            c.gridy = 1;
            c.gridwidth = 2;
            c.weighty = 1;
            c.anchor = GridBagConstraints.WEST;
            add(refresh, c);
        }

        private void addCard(GridBagConstraints c, int row, String title, String description) {
            JPanel card = new JPanel(new BorderLayout(4, 4));
            card.setBorder(new EmptyBorder(12, 12, 12, 12));
            card.putClientProperty(FlatClientProperties.STYLE, "arc: 20; background: mix(@accentColor, @background, 4%)");
            JLabel heading = new JLabel(title);
            heading.putClientProperty(FlatClientProperties.STYLE, "font: bold $h3.font");
            JLabel desc = new JLabel("<html><body style='width: 280px'>" + description + "</body></html>");
            desc.putClientProperty(FlatClientProperties.STYLE, "foreground: $Label.disabledForeground");

            JLabel status = switch (row) {
                case 0 -> javaLabel;
                default -> dockerLabel;
            };
            JLabel detail = switch (row) {
                case 0 -> javaDetail;
                default -> dockerDetail;
            };

            JPanel statusRow = new JPanel(new BorderLayout());
            statusRow.setOpaque(false);
            statusRow.add(status, BorderLayout.WEST);
            statusRow.add(detail, BorderLayout.EAST);

            card.add(heading, BorderLayout.NORTH);
            card.add(desc, BorderLayout.CENTER);
            card.add(statusRow, BorderLayout.SOUTH);

            c.gridx = row;
            c.gridy = 0;
            c.gridwidth = 1;
            c.weighty = 0;
            c.fill = GridBagConstraints.BOTH;
            c.weightx = 1;
            add(card, c);
        }

        void refresh() {
            appendStatus("Running prerequisite checks...");
            SwingWorker<Void, Void> worker = new SwingWorker<>() {
                CheckResult javaResult;
                CheckResult dockerResult;

                @Override
                protected Void doInBackground() {
                    javaResult = prerequisiteChecker.checkJavaSdk25();
                    dockerResult = prerequisiteChecker.checkDocker();
                    return null;
                }

                @Override
                protected void done() {
                    apply(javaLabel, javaDetail, javaResult);
                    apply(dockerLabel, dockerDetail, dockerResult);
                    pass = javaResult.ok() && dockerResult.ok();
                    appendStatus(pass ? "Prerequisites passed." : "Prerequisites need attention before proceeding.");
                }
            };
            worker.execute();
        }

        private void apply(JLabel title, JLabel detail, CheckResult result) {
            title.setText(result.label());
            title.setForeground(result.ok() ? new Color(34, 139, 84) : new Color(197, 74, 74));
            detail.setText(result.detail());
        }

        boolean canProceed() {
            return pass;
        }
    }

    private final class DownloadPanel extends JPanel {
        private final JProgressBar progressBar = new JProgressBar(0, 100);
        private final JLabel phaseLabel = new JLabel("Waiting");
        private final JButton downloadButton = new JButton("Download / Check artifacts");
        private volatile boolean ready;

        DownloadPanel() {
            setLayout(new BorderLayout(12, 12));
            setBorder(new EmptyBorder(18, 0, 18, 0));

            JPanel info = new JPanel();
            info.setLayout(new BoxLayout(info, BoxLayout.Y_AXIS));
            JLabel heading = new JLabel("GitHub Releases Integration");
            heading.putClientProperty(FlatClientProperties.STYLE, "font: bold $h2.font");
            JLabel body = new JLabel("<html>Downloads the latest backend JAR and pulls the frontend Docker image (<tt>ghcr.io/pablog02/selextrace-frontend:latest</tt>). The backend JAR is cached and re-downloaded only when the release asset changes.</html>");
            info.add(heading);
            info.add(Box.createVerticalStrut(6));
            info.add(body);

            add(info, BorderLayout.NORTH);

            JPanel center = new JPanel(new BorderLayout(8, 8));
            progressBar.setStringPainted(true);
            center.add(progressBar, BorderLayout.NORTH);
            center.add(phaseLabel, BorderLayout.SOUTH);
            add(center, BorderLayout.CENTER);

            JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            downloadButton.addActionListener(e -> startDownload());
            actions.add(downloadButton);
            add(actions, BorderLayout.SOUTH);
        }

        void onEnter() {
            if (!ready) {
                startDownload();
            }
        }

        void startDownload() {
            downloadButton.setEnabled(false);
            ready = false;
            SwingWorker<ArtifactManager.ArtifactBundle, ArtifactManager.DownloadProgress> worker = new SwingWorker<>() {
                @Override
                protected ArtifactManager.ArtifactBundle doInBackground() throws Exception {
                    return artifactManager.downloadLatestArtifacts((message, percent) -> publish(new ArtifactManager.DownloadProgress(message, percent)));
                }

                @Override
                protected void process(List<ArtifactManager.DownloadProgress> chunks) {
                    ArtifactManager.DownloadProgress latest = chunks.get(chunks.size() - 1);
                    phaseLabel.setText(latest.message());
                    progressBar.setValue(latest.percent());
                    progressBar.setString(latest.percent() + "%");
                    appendStatus(latest.message() + " (" + latest.percent() + "%)");
                }

                @Override
                protected void done() {
                    try {
                        artifactBundle = get();
                        ready = true;
                        phaseLabel.setText("Artifacts ready");
                        progressBar.setValue(100);
                        progressBar.setString("100%");
                        appendStatus("Artifacts are cached and ready to use.");
                    } catch (Exception ex) {
                        phaseLabel.setText("Download failed");
                        progressBar.setValue(0);
                        appendStatus("Artifact download failed: " + ex.getMessage());
                        JOptionPane.showMessageDialog(LauncherFrame.this, ex.getMessage(), "Download error", JOptionPane.ERROR_MESSAGE);
                    } finally {
                        downloadButton.setEnabled(true);
                    }
                }
            };
            worker.execute();
        }

        boolean canProceed() {
            return ready && artifactBundle != null;
        }
    }

    private final class ConfigurationPanel extends JPanel {
        private final JTextField dbName = new JTextField(20);
        private final JTextField dbUser = new JTextField(20);
        private final JPasswordField dbPassword = new JPasswordField(20);
        private final JToggleButton revealDbPassword = new JToggleButton("Show");
        private final JSpinner pgMajor = new JSpinner(new SpinnerNumberModel(18, 18, 99, 1));
        private final JSpinner backendPort = new JSpinner(new SpinnerNumberModel(8080, 1, 65535, 1));
        private final JSpinner frontendPort = new JSpinner(new SpinnerNumberModel(4200, 1, 65535, 1));
        private final JTextField clientId = new JTextField(24);
        private final JPasswordField clientSecret = new JPasswordField(24);
        private final JToggleButton revealClientSecret = new JToggleButton("Show");
        private final JComboBox<String> bindAddress = new JComboBox<>(detectLocalAddresses().toArray(new String[0]));

        ConfigurationPanel() {
            setBorder(new EmptyBorder(10, 0, 10, 0));
            setLayout(new BorderLayout(12, 12));

            JPanel form = new JPanel(new GridBagLayout());
            GridBagConstraints c = new GridBagConstraints();
            c.insets = new Insets(6, 8, 6, 8);
            c.fill = GridBagConstraints.HORIZONTAL;
            c.weightx = 1;

            int row = 0;

            addLabelRow(form, c, row++, "Database name", dbName, "selextrace");
            addLabelRow(form, c, row++, "Database username", dbUser, "selextrace");
            addPasswordRow(form, c, row++, "Database password", dbPassword, revealDbPassword);
            addSpinnerRow(form, c, row++, "PostgreSQL major version", pgMajor);
            addSpinnerRow(form, c, row++, "Backend API port", backendPort);
            addSpinnerRow(form, c, row++, "Frontend port", frontendPort);
            addLabelRow(form, c, row++, "Google client ID", clientId, "");
            addPasswordRow(form, c, row++, "Google client secret", clientSecret, revealClientSecret);
            addLabelRowCombo(form, c, row++, "Make available on", bindAddress, "Choose 'localhost' for this device only, or pick a network IP to allow other devices on your network to connect.");

            JPanel wrapper = new JPanel(new BorderLayout());
            wrapper.add(form, BorderLayout.NORTH);
            add(wrapper, BorderLayout.CENTER);

            revealDbPassword.addActionListener(this::toggleDbPassword);
            revealClientSecret.addActionListener(this::toggleClientSecret);
        }

        void loadFromConfig(LauncherConfig cfg) {
            dbName.setText(cfg.databaseName());
            dbUser.setText(cfg.databaseUsername());
            dbPassword.setText(cfg.databasePassword());
            pgMajor.setValue(cfg.postgresqlMajorVersion());
            backendPort.setValue(cfg.backendPort());
            frontendPort.setValue(cfg.frontendPort());
            clientId.setText(cfg.googleClientId());
            clientSecret.setText(cfg.googleClientSecret());
            bindAddress.setSelectedItem(cfg.bindAddress());
            if (bindAddress.getSelectedIndex() == -1 && bindAddress.getItemCount() > 0) {
                bindAddress.setSelectedIndex(0);
            }
        }

        LauncherConfig toConfig(boolean darkTheme) {
            int pgVersion = (int) pgMajor.getValue();
            if (pgVersion < 18) {
                throw new IllegalArgumentException("Target PostgreSQL major version must be 18 or higher.");
            }

            String databaseNameValue = dbName.getText().trim();
            String databaseUserValue = dbUser.getText().trim();
            String passwordValue = new String(dbPassword.getPassword());
            String clientIdValue = clientId.getText().trim();
            String clientSecretValue = new String(clientSecret.getPassword());
            String bindAddressValue = (String) bindAddress.getSelectedItem();
            if (bindAddressValue == null || bindAddressValue.isBlank()) {
                bindAddressValue = "localhost";
            }

            validateRequired(databaseNameValue, "Database name");
            validateRequired(databaseUserValue, "Database username");
            validateRequired(passwordValue, "Database password");

            return new LauncherConfig(
                    darkTheme,
                    databaseNameValue,
                    databaseUserValue,
                    passwordValue,
                    pgVersion,
                    (int) backendPort.getValue(),
                    (int) frontendPort.getValue(),
                    clientIdValue,
                    clientSecretValue,
                    bindAddressValue
            );
        }

        private void validateRequired(String value, String label) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(label + " is required.");
            }
        }

        private void addLabelRowCombo(JPanel form, GridBagConstraints c, int row, String label, JComboBox<String> combo, String tooltip) {
            JLabel l = new JLabel(label);
            l.setToolTipText(tooltip);
            c.gridx = 0;
            c.gridy = row;
            c.weightx = 0.28;
            form.add(l, c);

            combo.setToolTipText(tooltip);
            c.gridx = 1;
            c.weightx = 0.72;
            form.add(combo, c);
        }

        private void addLabelRow(JPanel form, GridBagConstraints c, int row, String label, JTextField field, String placeholder) {
            JLabel l = new JLabel(label);
            c.gridx = 0;
            c.gridy = row;
            c.weightx = 0.28;
            form.add(l, c);

            if (field.getText().isBlank() && !placeholder.isEmpty()) {
                field.setText(placeholder);
            }
            c.gridx = 1;
            c.weightx = 0.72;
            form.add(field, c);
        }

        private void addPasswordRow(JPanel form, GridBagConstraints c, int row, String label, JPasswordField field, JToggleButton toggle) {
            JLabel l = new JLabel(label);
            c.gridx = 0;
            c.gridy = row;
            c.weightx = 0.28;
            form.add(l, c);

            JPanel rowPanel = new JPanel(new BorderLayout(6, 0));
            rowPanel.add(field, BorderLayout.CENTER);
            rowPanel.add(toggle, BorderLayout.EAST);
            c.gridx = 1;
            c.weightx = 0.72;
            form.add(rowPanel, c);
        }

        private void addSpinnerRow(JPanel form, GridBagConstraints c, int row, String label, JSpinner spinner) {
            JLabel l = new JLabel(label);
            c.gridx = 0;
            c.gridy = row;
            c.weightx = 0.28;
            form.add(l, c);

            // Configure spinner editor to not use thousand grouping separators and to align text to the left
            JSpinner.NumberEditor editor = new JSpinner.NumberEditor(spinner, "#");
            editor.getTextField().setHorizontalAlignment(JTextField.LEFT);
            spinner.setEditor(editor);

            c.gridx = 1;
            c.weightx = 0.72;
            form.add(spinner, c);
        }

        private void toggleDbPassword(ActionEvent e) {
            dbPassword.setEchoChar(revealDbPassword.isSelected() ? (char) 0 : '•');
            revealDbPassword.setText(revealDbPassword.isSelected() ? "Hide" : "Show");
        }

        private void toggleClientSecret(ActionEvent e) {
            clientSecret.setEchoChar(revealClientSecret.isSelected() ? (char) 0 : '•');
            revealClientSecret.setText(revealClientSecret.isSelected() ? "Hide" : "Show");
        }
    }

    private final class ServicesPanel extends JPanel {
        private final JLabel postgresState = createStateLabel();
        private final JLabel backendState = createStateLabel();
        private final JLabel frontendState = createStateLabel();
        private final JLabel details = new JLabel("Idle");
        private final JButton startButton = new JButton("Start services");
        private final JButton stopButton = new JButton("Stop all");
        private final JButton browserButton = new JButton("Open browser");
        private volatile ServiceManager.ServiceSnapshot snapshot = new ServiceManager.ServiceSnapshot(ServiceManager.Status.STOPPED, ServiceManager.Status.STOPPED, ServiceManager.Status.STOPPED, "Idle");

        private final JTextArea logsArea = new JTextArea(14, 80);
        private int logOffset = 0;
        private Timer logTimer;

        ServicesPanel() {
            setLayout(new BorderLayout(12, 12));
            setBorder(new EmptyBorder(14, 0, 14, 0));

            JPanel top = new JPanel(new GridLayout(1, 3, 12, 12));
            top.add(serviceCard("PostgreSQL Container", postgresState));
            top.add(serviceCard("Spring Boot Backend", backendState));
            top.add(serviceCard("Angular Frontend", frontendState));
            add(top, BorderLayout.NORTH);

            JPanel center = new JPanel(new BorderLayout(8, 8));
            details.setBorder(new EmptyBorder(12, 12, 12, 12));
            center.add(details, BorderLayout.NORTH);

            logsArea.setEditable(false);
            logsArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            logsArea.setText("Service lifecycle logs appear here.\n");
            center.add(new JScrollPane(logsArea), BorderLayout.CENTER);
            add(center, BorderLayout.CENTER);

            JPanel actions = new JPanel(new BorderLayout());
            actions.setOpaque(false);

            JPanel leftActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            leftActions.setOpaque(false);
            startButton.addActionListener(e -> startServices());
            stopButton.addActionListener(e -> stopServices());
            leftActions.add(startButton);
            leftActions.add(stopButton);

            JPanel rightActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            rightActions.setOpaque(false);
            browserButton.addActionListener(e -> serviceManager.openBrowser(config.bindAddress(), config.frontendPort()));
            rightActions.add(browserButton);

            actions.add(leftActions, BorderLayout.WEST);
            actions.add(rightActions, BorderLayout.EAST);
            add(actions, BorderLayout.SOUTH);

            updateButtons();
        }

        void onEnter() {
            updateSnapshot(new ServiceManager.ServiceSnapshot(ServiceManager.Status.STOPPED, ServiceManager.Status.STOPPED, ServiceManager.Status.STOPPED, "Ready to start services"));
            if (logTimer != null) {
                logTimer.stop();
            }
        }

        private JComponent serviceCard(String title, JLabel stateLabel) {
            JPanel panel = new JPanel(new BorderLayout(6, 6));
            panel.setBorder(new EmptyBorder(12, 12, 12, 12));
            panel.putClientProperty(FlatClientProperties.STYLE, "arc: 20; background: mix(@accentColor, @background, 4%)");
            JLabel label = new JLabel(title);
            label.putClientProperty(FlatClientProperties.STYLE, "font: bold $h3.font");
            panel.add(label, BorderLayout.NORTH);
            panel.add(stateLabel, BorderLayout.CENTER);
            return panel;
        }

        private JLabel createStateLabel() {
            JLabel label = new JLabel("STOPPED");
            label.putClientProperty(FlatClientProperties.STYLE, "font: bold $h2.font");
            return label;
        }

        private void startServices() {
            if (artifactBundle == null) {
                JOptionPane.showMessageDialog(LauncherFrame.this, "Download the artifacts first.", "Missing artifacts", JOptionPane.WARNING_MESSAGE);
                return;
            }
            try {
                config = configurationPanel.toConfig(config.darkTheme());
                configStore.save(config);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(LauncherFrame.this, ex.getMessage(), "Configuration error", JOptionPane.ERROR_MESSAGE);
                return;
            }

            startButton.setEnabled(false);
            appendStatus("Starting all services...");

            serviceHandle.logs().clear();
            logsArea.setText("");
            logOffset = 0;
            if (logTimer != null) {
                logTimer.stop();
            }
            logTimer = new Timer(200, e -> {
                List<String> logs = serviceHandle.logs();
                int size = logs.size();
                if (logOffset < size) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = logOffset; i < size; i++) {
                        sb.append(logs.get(i)).append("\n");
                    }
                    logsArea.append(sb.toString());
                    logOffset = size;
                }
            });
            logTimer.start();

            SwingWorker<Void, Void> worker = new SwingWorker<>() {
                @Override
                protected Void doInBackground() throws Exception {
                    servicesRunning.set(true);
                    serviceManager.startAll(serviceHandle, config, artifactBundle, ServicesPanel.this::updateSnapshot);
                    return null;
                }

                @Override
                protected void done() {
                    try {
                        get();
                        appendStatus("Services are running.");
                    } catch (Exception ex) {
                        servicesRunning.set(false);
                        updateSnapshot(new ServiceManager.ServiceSnapshot(ServiceManager.Status.ERROR, ServiceManager.Status.ERROR, ServiceManager.Status.ERROR, ex.getMessage()));
                        appendStatus("Service startup failed: " + ex.getMessage());
                        if (logTimer != null) {
                            logTimer.stop();
                        }
                        JOptionPane.showMessageDialog(LauncherFrame.this, ex.getMessage(), "Startup error", JOptionPane.ERROR_MESSAGE);
                    } finally {
                        updateButtons();
                    }
                }
            };
            worker.execute();
        }

        private void stopServices() {
            servicesRunning.set(false);
            appendStatus("Stopping services...");
            serviceManager.stopAll(serviceHandle, ServicesPanel.this::updateSnapshot);
            if (logTimer != null) {
                logTimer.stop();
            }
            updateButtons();
        }

        private void updateSnapshot(ServiceManager.ServiceSnapshot snap) {
            snapshot = snap;
            SwingUtilities.invokeLater(() -> {
                postgresState.setText(snap.postgres().name());
                backendState.setText(snap.backend().name());
                frontendState.setText(snap.frontend().name());
                details.setText(snap.details());
                updateButtons();
            });
        }

        private void updateButtons() {
            boolean running = servicesRunning.get();
            startButton.setEnabled(!running);
            stopButton.setEnabled(running);
            browserButton.setEnabled(running && snapshot.frontend() == ServiceManager.Status.RUNNING);
        }
    }
}
