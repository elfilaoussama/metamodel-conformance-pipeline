package com.javapipeline.desktop;

import com.javapipeline.core.*;
import com.javapipeline.github.JGitHubRepositoryIngestionService;
import com.javapipeline.spoon.ExtractionJsonWriter;
import com.javapipeline.spoon.SpoonJavaExtractionService;
import com.javapipeline.verification.AlloyInEcoreVerificationService;
import com.javapipeline.verification.VerificationOutcome;
import com.javapipeline.verification.VerificationRequest;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.ActionEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.prefs.Preferences;

final class AnalysisFrame extends JFrame {
    private static final String DEFAULT_WORKSPACE = "workspace/repositories";
    private static final String DEFAULT_OUTPUT = "analysis-output";
    private static final String DEFAULT_VERIFIER = "modules/verification-cli";
    private static final String DEFAULT_METAMODEL = DEFAULT_VERIFIER + "/src/main/resources/class_level_structural_kernel.als";

    private final Preferences preferences = Preferences.userNodeForPackage(AnalysisFrame.class);
    private final RepositoryQueueModel queueModel = new RepositoryQueueModel();
    private final JTable queueTable = new JTable(queueModel);
    private final JTextArea urlsArea = new JTextArea(4, 70);
    private final JTextField workspaceField = new JTextField();
    private final JTextField outputField = new JTextField();
    private final JCheckBox verifyBox = new JCheckBox("Verify extracted model with AlloyInEcore");
    private final JTextField verifierField = new JTextField();
    private final JTextField metamodelField = new JTextField();
    private final JSpinner depthSpinner = new JSpinner(new SpinnerNumberModel(1, 1, 100, 1));
    private final JSpinner complianceSpinner = new JSpinner(new SpinnerNumberModel(17, 8, 23, 1));
    private final JCheckBox includeTestsBox = new JCheckBox("Include test sources");
    private final JCheckBox reuseBox = new JCheckBox("Reuse existing clones", true);
    private final JTextArea logArea = new JTextArea();
    private final VerificationTableModel verificationModel = new VerificationTableModel();
    private final JTable verificationTable = new JTable(verificationModel);
    private final JProgressBar progressBar = new JProgressBar();
    private final JLabel statusLabel = new JLabel("Ready");
    private final JButton addButton = new JButton("Add to queue");
    private final JButton searchGitHubButton = new JButton("Search GitHub...");
    private final JButton removeButton = new JButton("Remove selected");
    private final JButton clearButton = new JButton("Clear");
    private final JButton startButton = new JButton("Start");
    private final JButton cancelButton = new JButton("Cancel");
    private AnalysisWorker activeWorker;

    AnalysisFrame() {
        super("Java Analysis Platform");
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(1050, 720));
        setSize(1200, 820);
        setLocationRelativeTo(null);

        JPanel content = new JPanel(new BorderLayout(10, 10));
        content.setBorder(new EmptyBorder(12, 12, 12, 12));
        setContentPane(content);
        content.add(buildConfigurationPanel(), BorderLayout.NORTH);
        content.add(buildMainPanel(), BorderLayout.CENTER);
        content.add(buildStatusPanel(), BorderLayout.SOUTH);

        loadPreferences();
        wireActions();
        configureTable();
        updateVerificationControls();
        setBusy(false);
    }

    private JComponent buildConfigurationPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.fill = GridBagConstraints.HORIZONTAL;

        JPanel urlActions = new JPanel(new GridLayout(2, 1, 0, 4));
        urlActions.add(addButton);
        urlActions.add(searchGitHubButton);
        addRow(panel, c, 0, "GitHub URLs", new JScrollPane(urlsArea), urlActions);
        addRow(panel, c, 1, "Clone workspace", workspaceField, browseButton(workspaceField));
        addRow(panel, c, 2, "Analysis output", outputField, browseButton(outputField));
        addRow(panel, c, 3, "Verifier module", verifierField, browseButton(verifierField));
        addRow(panel, c, 4, "Alloy metamodel", metamodelField, fileBrowseButton(metamodelField, "recore"));

        JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        options.add(new JLabel("Clone depth"));
        options.add(depthSpinner);
        options.add(new JLabel("Java level"));
        options.add(complianceSpinner);
        options.add(includeTestsBox);
        options.add(reuseBox);
        options.add(verifyBox);
        c.gridx = 0; c.gridy = 5; c.weightx = 0;
        panel.add(new JLabel("Options"), c);
        c.gridx = 1; c.weightx = 1; c.gridwidth = 2;
        panel.add(options, c);
        return panel;
    }

    private void addRow(JPanel panel, GridBagConstraints c, int row, String label, JComponent component, JComponent action) {
        c.gridwidth = 1; c.gridx = 0; c.gridy = row; c.weightx = 0;
        panel.add(new JLabel(label), c);
        c.gridx = 1; c.weightx = 1;
        panel.add(component, c);
        c.gridx = 2; c.weightx = 0;
        panel.add(action, c);
    }

    private JComponent buildMainPanel() {
        JPanel queueActions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        queueActions.add(removeButton);
        queueActions.add(clearButton);
        queueActions.add(startButton);
        queueActions.add(cancelButton);
        JButton openOutput = new JButton("Open output");
        openOutput.addActionListener(event -> openOutputFolder());
        queueActions.add(openOutput);

        JPanel queuePanel = new JPanel(new BorderLayout(5, 5));
        queuePanel.add(queueActions, BorderLayout.NORTH);
        queuePanel.add(new JScrollPane(queueTable), BorderLayout.CENTER);

        logArea.setEditable(false);
        logArea.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12));
        JScrollPane logScroll = new JScrollPane(logArea);
        verificationTable.setAutoCreateRowSorter(true);
        verificationTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        int[] verificationWidths = {180, 80, 190, 55, 520, 340};
        for (int i = 0; i < verificationWidths.length; i++) {
            verificationTable.getColumnModel().getColumn(i).setPreferredWidth(verificationWidths[i]);
        }
        JTabbedPane resultTabs = new JTabbedPane();
        resultTabs.addTab("Activity", logScroll);
        resultTabs.addTab("Verification results", new JScrollPane(verificationTable));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, queuePanel, resultTabs);
        split.setResizeWeight(0.62);
        return split;
    }

    private JComponent buildStatusPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 0));
        progressBar.setStringPainted(true);
        progressBar.setPreferredSize(new Dimension(300, 20));
        panel.add(statusLabel, BorderLayout.CENTER);
        panel.add(progressBar, BorderLayout.EAST);
        return panel;
    }

    private void configureTable() {
        queueTable.setAutoCreateRowSorter(true);
        queueTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        queueTable.getColumnModel().getColumn(0).setPreferredWidth(330);
        queueTable.getColumnModel().getColumn(2).setPreferredWidth(260);
        queueTable.getColumnModel().getColumn(4).setPreferredWidth(280);
    }

    private void wireActions() {
        addButton.addActionListener(this::addUrls);
        searchGitHubButton.addActionListener(event ->
                new GitHubSearchDialog(this, this::addSearchResults).setVisible(true));
        removeButton.addActionListener(event -> removeSelected());
        clearButton.addActionListener(event -> queueModel.clear());
        startButton.addActionListener(event -> startAnalysis());
        cancelButton.addActionListener(event -> {
            if (activeWorker != null) activeWorker.cancel(false);
        });
        verifyBox.addActionListener(event -> updateVerificationControls());
    }

    private void addUrls(ActionEvent ignored) {
        int depth = (Integer) depthSpinner.getValue();
        for (String line : urlsArea.getText().lines().toList()) {
            String url = line.trim();
            if (url.isEmpty()) continue;
            try {
                RepositoryRequest.fromGitHubUrl(url, depth, ExistingRepositoryPolicy.REUSE);
                queueModel.add(url);
            } catch (IllegalArgumentException ex) {
                appendLog("Rejected " + url + ": " + ex.getMessage());
            }
        }
        urlsArea.setText("");
    }

    private void removeSelected() {
        int[] rows = queueTable.getSelectedRows();
        for (int index = rows.length - 1; index >= 0; index--) {
            queueModel.remove(queueTable.convertRowIndexToModel(rows[index]));
        }
    }

    private void addSearchResults(List<String> urls) {
        int added = 0;
        for (String url : urls) {
            int before = queueModel.getRowCount();
            queueModel.add(url);
            if (queueModel.getRowCount() > before) added++;
        }
        appendLog("GitHub search added " + added + " new repositories (" + (urls.size() - added)
                + " duplicates skipped).");
    }

    private void startAnalysis() {
        List<RepositoryQueueModel.Item> items = queueModel.snapshot();
        if (items.isEmpty()) {
            JOptionPane.showMessageDialog(this, "Add at least one GitHub repository.",
                    "Nothing to analyze", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        try {
            RunConfiguration configuration = new RunConfiguration(
                    requiredPath(workspaceField.getText(), "Clone workspace"),
                    requiredPath(outputField.getText(), "Analysis output"),
                    (Integer) depthSpinner.getValue(),
                    (Integer) complianceSpinner.getValue(),
                    includeTestsBox.isSelected(), reuseBox.isSelected(), verifyBox.isSelected(),
                    verifyBox.isSelected() ? requiredPath(verifierField.getText(), "Verifier module") : null,
                    verifyBox.isSelected() ? requiredPath(metamodelField.getText(), "Alloy metamodel") : null);
            Files.createDirectories(configuration.workspace());
            Files.createDirectories(configuration.output());
            savePreferences();
            progressBar.setValue(0);
            progressBar.setMaximum(items.size());
            progressBar.setIndeterminate(false);
            setBusy(true);
            verificationModel.clear();
            activeWorker = new AnalysisWorker(items, configuration);
            activeWorker.execute();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Invalid configuration", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static Path requiredPath(String raw, String label) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException(label + " is required");
        return Path.of(raw.trim()).toAbsolutePath().normalize();
    }

    private void setBusy(boolean busy) {
        startButton.setEnabled(!busy);
        addButton.setEnabled(!busy);
        searchGitHubButton.setEnabled(!busy);
        removeButton.setEnabled(!busy);
        clearButton.setEnabled(!busy);
        cancelButton.setEnabled(busy);
        verifyBox.setEnabled(!busy);
        if (!busy) updateVerificationControls();
    }

    private void updateVerificationControls() {
        boolean enabled = verifyBox.isSelected() && activeWorker == null;
        verifierField.setEnabled(enabled);
        metamodelField.setEnabled(enabled);
        verifyBox.setToolTipText("Strict conformance: non-derived model relations are fixed to the Spoon extraction");
        metamodelField.setToolTipText("The selected .recore file is parsed again for every repository run");
    }

    private JButton browseButton(JTextField target) {
        JButton button = new JButton("Browse");
        button.addActionListener(event -> {
            JFileChooser chooser = new JFileChooser(target.getText());
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                target.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });
        return button;
    }

    private JButton fileBrowseButton(JTextField target, String extension) {
        JButton button = new JButton("Browse");
        button.addActionListener(event -> {
            JFileChooser chooser = new JFileChooser(target.getText());
            chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
            chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                    "AlloyInEcore metamodel (*." + extension + ")", extension));
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                target.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });
        return button;
    }

    private void openOutputFolder() {
        try {
            Path output = Path.of(outputField.getText()).toAbsolutePath().normalize();
            if (!Files.isDirectory(output)) throw new IllegalArgumentException("Output folder does not exist");
            if (!Desktop.isDesktopSupported()) throw new IllegalStateException("Desktop integration is unavailable");
            Desktop.getDesktop().open(output.toFile());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Cannot open output", JOptionPane.WARNING_MESSAGE);
        }
    }

    private void appendLog(String message) {
        logArea.append(message + System.lineSeparator());
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    private void loadPreferences() {
        workspaceField.setText(preferences.get("workspace", DEFAULT_WORKSPACE));
        outputField.setText(preferences.get("output", DEFAULT_OUTPUT));
        verifierField.setText(staleToDefault("verifier", DEFAULT_VERIFIER, true));
        metamodelField.setText(staleToDefault("metamodel", DEFAULT_METAMODEL, false));
        verifyBox.setSelected(preferences.getBoolean("verify", false));
        includeTestsBox.setSelected(preferences.getBoolean("includeTests", false));
        reuseBox.setSelected(preferences.getBoolean("reuse", true));
    }

    private String staleToDefault(String key, String defaultValue, boolean isDirectory) {
        String saved = preferences.get(key, defaultValue);
        Path p = Path.of(saved).toAbsolutePath().normalize();
        boolean exists = isDirectory ? Files.isDirectory(p) : Files.isRegularFile(p);
        if (!exists) {
            Path def = Path.of(defaultValue).toAbsolutePath().normalize();
            if (isDirectory ? Files.isDirectory(def) : Files.isRegularFile(def)) {
                return defaultValue;
            }
        }
        return saved;
    }

    private void savePreferences() {
        preferences.put("workspace", workspaceField.getText().trim());
        preferences.put("output", outputField.getText().trim());
        preferences.put("verifier", verifierField.getText().trim());
        preferences.put("metamodel", metamodelField.getText().trim());
        preferences.putBoolean("verify", verifyBox.isSelected());
        preferences.putBoolean("includeTests", includeTestsBox.isSelected());
        preferences.putBoolean("reuse", reuseBox.isSelected());
    }

    private record RunConfiguration(
            Path workspace, Path output, int depth, int compliance, boolean includeTests, boolean reuse,
            boolean verify, Path verifierHome, Path metamodel
    ) { }

    private record UiEvent(
            RepositoryQueueModel.Item item,
            RepositoryQueueModel.Status status,
            String activity,
            Integer typeCount,
            Path output,
            String log,
            VerificationOutcome verification
    ) { }

    private final class AnalysisWorker extends SwingWorker<Void, UiEvent> {
        private final List<RepositoryQueueModel.Item> items;
        private final RunConfiguration configuration;
        private final RepositoryIngestionService ingestion = new JGitHubRepositoryIngestionService();
        private final JavaExtractionService extraction = new SpoonJavaExtractionService();
        private final ExtractionJsonWriter writer = new ExtractionJsonWriter();
        private final AlloyInEcoreVerificationService verification = new AlloyInEcoreVerificationService();
        private int completed;

        private AnalysisWorker(List<RepositoryQueueModel.Item> items, RunConfiguration configuration) {
            this.items = items;
            this.configuration = configuration;
        }

        @Override
        protected Void doInBackground() {
            for (RepositoryQueueModel.Item item : items) {
                if (isCancelled()) {
                    publish(new UiEvent(item, RepositoryQueueModel.Status.CANCELLED,
                            "Cancelled", null, null, "Cancelled " + item.url, null));
                    break;
                }
                try {
                    ExistingRepositoryPolicy policy = configuration.reuse()
                            ? ExistingRepositoryPolicy.REUSE : ExistingRepositoryPolicy.FAIL;
                    RepositoryRequest request = RepositoryRequest.fromGitHubUrl(
                            item.url, configuration.depth(), policy);
                    publish(new UiEvent(item, RepositoryQueueModel.Status.INGESTING,
                            "Preparing clone", null, null, "Starting " + request.coordinate(), null));

                    IngestedRepository repository = ingestion.ingest(
                            request, configuration.workspace(), event -> publish(progressEvent(item, event)),
                            this::isCancelled);
                    publish(new UiEvent(item, RepositoryQueueModel.Status.EXTRACTING,
                            "Building Spoon model", null, null,
                            (repository.reused() ? "Reusing " : "Cloned ") + request.coordinate()
                                    + " at " + repository.revision(), null));

                    Path output = configuration.output()
                            .resolve(request.owner() + "__" + request.name()).resolve("extraction.json");
                    AnalysisCache cache = AnalysisCache.load(output.getParent());
                    String extractionKey = AnalysisCache.extractionKey(repository.directory(), repository.revision(),
                            configuration.compliance(), configuration.includeTests());
                    int typeCount;
                    if (cache.hasExtraction(extractionKey, output)) {
                        typeCount = cache.typeCount();
                        publish(new UiEvent(item, RepositoryQueueModel.Status.EXTRACTING,
                                "Reusing cached Spoon extraction", typeCount, output,
                                "Cache hit for " + request.coordinate() + ": Spoon extraction skipped", null));
                    } else {
                        var result = extraction.extract(
                                request.coordinate(), repository.directory(),
                                new ExtractionOptions(configuration.compliance(), configuration.includeTests()),
                                event -> publish(progressEvent(item, event)), this::isCancelled);
                        writer.write(result, output);
                        typeCount = result.types().size();
                        cache.recordExtraction(extractionKey, typeCount);
                    }
                    VerificationOutcome verificationOutcome = null;
                    if (configuration.verify()) {
                        publish(new UiEvent(item, RepositoryQueueModel.Status.VERIFYING,
                                "Checking AlloyInEcore constraints", typeCount, output,
                                "Verifying " + request.coordinate() + " with " + configuration.metamodel(), null));
                        Path verificationOutput = output.getParent().resolve("verification");
                        String verificationKey = AnalysisCache.verificationKey(extractionKey, configuration.metamodel());
                        Path jsonReport = verificationOutput.resolve("verification-report.json");
                        Path csvReport = verificationOutput.resolve("verification-report.csv");
                        if (cache.hasVerification(verificationKey, jsonReport, csvReport)) {
                            verificationOutcome = verification.readExisting(verificationOutput);
                            publish(new UiEvent(item, RepositoryQueueModel.Status.VERIFYING,
                                    "Reusing cached verification", typeCount, output,
                                    "Cache hit for " + request.coordinate() + ": Alloy solver skipped", null));
                        } else {
                            verificationOutcome = verification.verify(new VerificationRequest(
                                            configuration.verifierHome(), configuration.metamodel(), output, verificationOutput),
                                    event -> publish(progressEvent(item, event)), this::isCancelled);
                            cache.recordVerification(verificationKey);
                        }
                    }
                    completed++;
                    RepositoryQueueModel.Status finalStatus = verificationOutcome != null
                            && verificationOutcome.status() == VerificationOutcome.Status.UNSAT
                            ? RepositoryQueueModel.Status.VIOLATIONS : RepositoryQueueModel.Status.COMPLETED;
                    String activity = verificationOutcome == null ? "Extraction completed"
                            : verificationOutcome.status() + " — " + verificationOutcome.violations().size() + " violation(s)";
                    publish(new UiEvent(item, finalStatus, activity, typeCount, output,
                            "Completed " + request.coordinate() + ": " + typeCount + " types; " + activity,
                            verificationOutcome));
                } catch (CancellationException ex) {
                    publish(new UiEvent(item, RepositoryQueueModel.Status.CANCELLED,
                            "Cancelled", null, null, "Cancelled " + item.url, null));
                    break;
                } catch (Exception ex) {
                    if (isCancelled()) {
                        publish(new UiEvent(item, RepositoryQueueModel.Status.CANCELLED,
                                "Cancelled", null, null, "Cancelled " + item.url, null));
                        break;
                    }
                    completed++;
                    publish(new UiEvent(item, RepositoryQueueModel.Status.FAILED,
                            ex.getMessage(), null, null, "Failed " + item.url + ": " + ex.getMessage(), null));
                }
            }
            return null;
        }

        private UiEvent progressEvent(RepositoryQueueModel.Item item, ProgressEvent event) {
            String activity = event.message();
            if (event.total() > 0) {
                long percent = Math.min(100, Math.round(event.completed() * 100.0 / event.total()));
                activity += " (" + percent + "%)";
            }
            RepositoryQueueModel.Status status = event.stage() == ProgressEvent.Stage.CLONING
                    ? RepositoryQueueModel.Status.INGESTING
                    : event.stage() == ProgressEvent.Stage.VERIFYING
                    ? RepositoryQueueModel.Status.VERIFYING : RepositoryQueueModel.Status.EXTRACTING;
            return new UiEvent(item, status, activity, null, null, null, null);
        }

        @Override
        protected void process(List<UiEvent> chunks) {
            for (UiEvent event : chunks) {
                RepositoryQueueModel.Item item = event.item();
                item.status = event.status();
                item.activity = event.activity();
                if (event.typeCount() != null) item.typeCount = event.typeCount();
                if (event.output() != null) item.output = event.output();
                queueModel.changed(item);
                if (event.log() != null) appendLog(event.log());
                if (event.verification() != null) {
                    verificationModel.add(item.url, event.verification());
                }
                statusLabel.setText(event.activity());
            }
            progressBar.setValue(completed);
            progressBar.setString(completed + " / " + items.size());
        }

        @Override
        protected void done() {
            try {
                get();
                statusLabel.setText(isCancelled() ? "Cancelled" : "Finished");
            } catch (CancellationException ex) {
                statusLabel.setText("Cancelled");
            } catch (Exception ex) {
                statusLabel.setText("Unexpected failure");
                appendLog("Unexpected worker failure: " + ex.getMessage());
            } finally {
                setBusy(false);
                activeWorker = null;
            }
        }
    }
}
