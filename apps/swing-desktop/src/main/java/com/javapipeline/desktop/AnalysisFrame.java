package com.javapipeline.desktop;

import com.javapipeline.core.*;
import com.javapipeline.cpp.CppExtractionService;
import com.javapipeline.github.JGitHubRepositoryIngestionService;
import com.javapipeline.python.PythonExtractionService;
import com.javapipeline.spoon.ExtractionJsonWriter;
import com.javapipeline.spoon.SpoonJavaExtractionService;
import com.javapipeline.verification.AlloyInEcoreVerificationService;
import com.javapipeline.verification.VerificationOutcome;
import com.javapipeline.verification.VerificationRequest;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.Desktop;
import java.awt.event.ActionEvent;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class AnalysisFrame extends JFrame {
    private static final String DEFAULT_WORKSPACE = "workspace/repositories";
    private static final String DEFAULT_OUTPUT = "analysis-output";
    private static final String DEFAULT_VERIFIER = "modules/verification-cli";
    private static final String DEFAULT_METAMODEL = DEFAULT_VERIFIER + "/src/main/resources/kernel_v2_obligation.als";
    private static final String PREF_WORKSPACE = "workspace";
    private static final String PREF_OUTPUT = "output";
    private static final String PREF_VERIFIER = "verifier";
    private static final String PREF_METAMODEL = "metamodel";
    private static final String PREF_VERIFY = "verify";
    private static final String PREF_INCLUDE_TESTS = "includeTests";
    private static final String PREF_REUSE = "reuse";
    private static final int TAB_ACTIVITY = 0;
    private static final int TAB_VERIFICATION = 1;

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
    private final JButton verifyExistingButton = new JButton("Verify existing");
    private final JTabbedPane resultTabs = new JTabbedPane();
    private SwingWorker<Void, UiEvent> activeWorker;

    AnalysisFrame() {
        super("Java Analysis Platform");
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setMinimumSize(new Dimension(900, 600));
        setSize(1200, 820);
        setLocationRelativeTo(null);

        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                if (activeWorker != null && !activeWorker.isDone()) {
                    int result = JOptionPane.showConfirmDialog(AnalysisFrame.this,
                            "Analysis is still running. Exit anyway?",
                            "Confirm exit", JOptionPane.YES_NO_OPTION);
                    if (result != JOptionPane.YES_OPTION) return;
                    activeWorker.cancel(true);
                }
                flushPreferences();
                dispose();
                System.exit(0);
            }
        });

        setJMenuBar(buildMenuBar());

        JPanel content = new JPanel(new BorderLayout(10, 10));
        content.setBorder(new EmptyBorder(12, 12, 12, 12));
        setContentPane(content);
        content.add(buildConfigurationPanel(), BorderLayout.NORTH);
        content.add(buildMainPanel(), BorderLayout.CENTER);
        content.add(buildStatusPanel(), BorderLayout.SOUTH);

        loadPreferences();
        wireActions();
        configureTable();
        installQueuePopup();
        updateVerificationControls();
        installFieldValidation();
        setBusy(false);
    }

    private JMenuBar buildMenuBar() {
        JMenuBar bar = new JMenuBar();

        JMenu fileMenu = new JMenu("File");
        JMenuItem loadExistingItem = new JMenuItem("Load existing repositories...");
        loadExistingItem.addActionListener(e -> loadExistingRepos());
        fileMenu.add(loadExistingItem);
        fileMenu.addSeparator();
        JMenuItem exitItem = new JMenuItem("Exit");
        exitItem.addActionListener(e -> dispatchEvent(new WindowEvent(this, WindowEvent.WINDOW_CLOSING)));
        fileMenu.add(exitItem);
        bar.add(fileMenu);

        JMenu exportMenu = new JMenu("Export");
        JMenuItem exportJsonItem = new JMenuItem("Verification results as JSON...");
        exportJsonItem.addActionListener(e -> exportVerification("json"));
        exportMenu.add(exportJsonItem);
        JMenuItem exportCsvItem = new JMenuItem("Verification results as CSV...");
        exportCsvItem.addActionListener(e -> exportVerification("csv"));
        exportMenu.add(exportCsvItem);
        JMenuItem exportSummaryItem = new JMenuItem("Verification summary as text...");
        exportSummaryItem.addActionListener(e -> exportVerification("txt"));
        exportMenu.add(exportSummaryItem);
        bar.add(exportMenu);

        JMenu helpMenu = new JMenu("Help");
        JMenuItem aboutItem = new JMenuItem("About");
        aboutItem.addActionListener(e -> JOptionPane.showMessageDialog(this,
                "Java Analysis Platform v0.2.0\n\n"
                        + "Multi-language structural analysis pipeline.\n"
                        + "Clone, extract, and verify Java, Python, and C++\n"
                        + "repositories using Spoon, Python AST, and Alloy.",
                "About", JOptionPane.INFORMATION_MESSAGE));
        helpMenu.add(aboutItem);
        bar.add(helpMenu);

        return bar;
    }

    private void loadExistingRepos() {
        Path outputBase = Path.of(outputField.getText().trim()).toAbsolutePath().normalize();
        if (!Files.isDirectory(outputBase)) {
            JOptionPane.showMessageDialog(this, "Output directory does not exist: " + outputBase,
                    "Cannot load", JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            List<Path> repoDirs;
            try (Stream<Path> stream = Files.list(outputBase)) {
                repoDirs = stream.filter(Files::isDirectory).sorted().toList();
            }
            if (repoDirs.isEmpty()) {
                JOptionPane.showMessageDialog(this, "No repositories found in " + outputBase,
                        "Nothing to load", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            int loaded = 0;
            AlloyInEcoreVerificationService verifier = new AlloyInEcoreVerificationService();
            for (Path dir : repoDirs) {
                String repoName = dir.getFileName().toString();
                queueModel.add(repoName);
                RepositoryQueueModel.Item item = queueModel.get(repoName);
                if (item == null) continue;
                item.language = detectLanguage(dir);
                Path extractionJson = dir.resolve("extraction.json");
                Path verificationDir = dir.resolve("verification");
                Path verificationJson = verificationDir.resolve("verification-report.json");
                if (Files.isRegularFile(extractionJson)) {
                    item.activity = "Extraction completed";
                    item.output = extractionJson;
                    if (Files.isRegularFile(verificationJson)) {
                        try {
                            VerificationOutcome outcome = verifier.readExisting(verificationDir);
                            item.status = outcome.status() == VerificationOutcome.Status.UNSAT
                                    ? RepositoryQueueModel.Status.VIOLATIONS : RepositoryQueueModel.Status.COMPLETED;
                            item.activity = outcome.status() + " — " + outcome.violations().size() + " violation(s)";
                            verificationModel.add(repoName, outcome);
                            updateVerificationTabBadge();
                        } catch (Exception ex) {
                            item.status = RepositoryQueueModel.Status.COMPLETED;
                            item.activity = "Extraction completed (verification unavailable)";
                        }
                    } else {
                        item.status = RepositoryQueueModel.Status.COMPLETED;
                        item.activity = "Extraction completed (no verification)";
                    }
                }
                queueModel.changed(item);
                loaded++;
            }
            appendLog("Loaded " + loaded + " existing repositories from " + outputBase);
            statusLabel.setText("Loaded " + loaded + " existing repositories");
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void exportVerification(String format) {
        if (verificationModel.getRowCount() == 0) {
            JOptionPane.showMessageDialog(this, "No verification results to export.",
                    "Nothing to export", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        JFileChooser chooser = new JFileChooser(outputField.getText().trim());
        String extension = "." + format;
        String description = switch (format) {
            case "json" -> "JSON file (*.json)";
            case "csv" -> "CSV file (*.csv)";
            default -> "Text file (*.txt)";
        };
        chooser.setSelectedFile(new java.io.File("verification-export" + extension));
        javax.swing.filechooser.FileNameExtensionFilter filter =
                new javax.swing.filechooser.FileNameExtensionFilter(description, format);
        chooser.setFileFilter(filter);
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path target = chooser.getSelectedFile().toPath();
        if (!target.getFileName().toString().contains(".")) {
            target = target.resolveSibling(target.getFileName() + extension);
        }
        try {
            String content = switch (format) {
                case "json" -> exportJson();
                case "csv" -> exportCsv();
                default -> exportTxt();
            };
            Files.writeString(target, content, StandardCharsets.UTF_8);
            appendLog("Exported verification results to " + target);
            statusLabel.setText("Exported " + target.getFileName());
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Export failed: " + ex.getMessage(),
                    "Export error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private String exportJson() {
        List<VerificationTableModel.Row> rows = verificationModel.allRows();
        StringBuilder sb = new StringBuilder();
        sb.append("[\n");
        for (int i = 0; i < rows.size(); i++) {
            VerificationTableModel.Row row = rows.get(i);
            sb.append("  {")
                    .append("\"repository\":\"").append(escapeJson(row.repository())).append("\",")
                    .append("\"result\":\"").append(escapeJson(row.result())).append("\",")
                    .append("\"constraint\":\"").append(escapeJson(row.constraint())).append("\",")
                    .append("\"line\":").append(row.line() == null ? "null" : row.line()).append(",")
                    .append("\"description\":\"").append(escapeJson(row.description())).append("\"")
                    .append("}");
            if (i < rows.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("]\n");
        return sb.toString();
    }

    private static String escapeJson(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private String exportCsv() {
        StringBuilder sb = new StringBuilder();
        sb.append("Repository,Result,Constraint,Line,Description\n");
        for (VerificationTableModel.Row row : verificationModel.allRows()) {
            sb.append(csvCell(row.repository())).append(',');
            sb.append(csvCell(row.result())).append(',');
            sb.append(csvCell(row.constraint())).append(',');
            sb.append(row.line() == null ? "" : row.line()).append(',');
            sb.append(csvCell(row.description())).append('\n');
        }
        return sb.toString();
    }

    private static String csvCell(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }

    private String exportTxt() {
        StringBuilder sb = new StringBuilder();
        sb.append("Verification Results Summary\n");
        sb.append("============================\n\n");
        long total = verificationModel.allRows().size();
        long violations = verificationModel.allRows().stream()
                .filter(r -> "UNSAT".equals(r.result())).count();
        long sat = verificationModel.allRows().stream()
                .filter(r -> "SAT".equals(r.result())).count();
        sb.append("Total entries: ").append(total).append('\n');
        sb.append("SAT: ").append(sat).append('\n');
        sb.append("UNSAT (violations): ").append(violations).append("\n\n");
        sb.append("--- Repositories ---\n");
        for (String repo : verificationModel.distinctRepositories()) {
            List<VerificationTableModel.Row> repoRows = verificationModel.filterByRepository(repo);
            String result = repoRows.isEmpty() ? "?" : repoRows.get(0).result();
            long repoViolations = repoRows.stream().filter(r -> r.constraint() != null && !r.constraint().isEmpty()).count();
            sb.append("  ").append(repo).append(": ").append(result);
            if (repoViolations > 0) sb.append(" (").append(repoViolations).append(" violation(s))");
            sb.append('\n');
        }
        if (violations > 0) {
            sb.append("\n--- Violation Details ---\n");
            for (VerificationTableModel.Row row : verificationModel.allRows()) {
                if ("UNSAT".equals(row.result())) {
                    sb.append("  ").append(row.repository())
                            .append(" | ").append(SwingUtils.blank(row.constraint(), "?"))
                            .append(" | ").append(SwingUtils.blank(row.description(), ""));
                    if (row.line() != null) sb.append(" [line ").append(row.line()).append(']');
                    sb.append('\n');
                }
            }
        }
        return sb.toString();
    }

    private JComponent buildConfigurationPanel() {
        JPanel container = new JPanel(new BorderLayout(8, 8));

        JPanel urlPanel = new JPanel(new BorderLayout(4, 4));
        JPanel urlActions = new JPanel(new GridLayout(2, 1, 0, 4));
        urlActions.add(addButton);
        urlActions.add(searchGitHubButton);
        urlPanel.add(new JLabel("GitHub URLs"), BorderLayout.NORTH);
        urlPanel.add(new JScrollPane(urlsArea), BorderLayout.CENTER);
        urlPanel.add(urlActions, BorderLayout.EAST);
        urlPanel.setBorder(BorderFactory.createTitledBorder("Repository sources"));

        JPanel pathPanel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 4, 3, 4);
        c.fill = GridBagConstraints.HORIZONTAL;
        addPathRow(pathPanel, c, 0, "Clone workspace", workspaceField);
        addPathRow(pathPanel, c, 1, "Analysis output", outputField);
        addPathRow(pathPanel, c, 2, "Verifier module", verifierField);
        addPathRow(pathPanel, c, 3, "Alloy metamodel", metamodelField);
        pathPanel.setBorder(BorderFactory.createTitledBorder("Paths"));

        JPanel options = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 4));
        options.add(new JLabel("Clone depth"));
        options.add(depthSpinner);
        options.add(new JLabel("Java level"));
        options.add(complianceSpinner);
        options.add(includeTestsBox);
        options.add(reuseBox);
        options.add(verifyBox);
        JPanel optionsPanel = new JPanel(new BorderLayout());
        optionsPanel.add(options, BorderLayout.WEST);
        optionsPanel.setBorder(BorderFactory.createTitledBorder("Options"));

        container.add(urlPanel, BorderLayout.NORTH);
        container.add(pathPanel, BorderLayout.CENTER);
        container.add(optionsPanel, BorderLayout.SOUTH);
        return container;
    }

    private void addPathRow(JPanel panel, GridBagConstraints c, int row, String label, JTextField field) {
        JPanel rowPanel = new JPanel(new BorderLayout(4, 0));
        rowPanel.add(field, BorderLayout.CENTER);
        rowPanel.add(browseButton(field), BorderLayout.EAST);
        c.gridx = 0; c.gridy = row; c.weightx = 0; c.gridwidth = 1;
        panel.add(new JLabel(label), c);
        c.gridx = 1; c.weightx = 1;
        panel.add(rowPanel, c);
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
        queueActions.add(verifyExistingButton);

        JPanel queuePanel = new JPanel(new BorderLayout(5, 5));
        queuePanel.add(queueActions, BorderLayout.NORTH);
        queuePanel.add(new JScrollPane(queueTable), BorderLayout.CENTER);

        logArea.setEditable(false);
        logArea.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12));
        JScrollPane logScroll = new JScrollPane(logArea);
        verificationTable.setAutoCreateRowSorter(true);
        verificationTable.setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
        int[] verificationWidths = {180, 65, 160, 45, 400, 200};
        for (int i = 0; i < verificationWidths.length && i < verificationTable.getColumnCount(); i++) {
            verificationTable.getColumnModel().getColumn(i).setPreferredWidth(verificationWidths[i]);
        }
        resultTabs.addTab("Activity", logScroll);
        resultTabs.addTab("Verification results", new JScrollPane(verificationTable));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, queuePanel, resultTabs);
        split.setResizeWeight(0.6);
        split.setOneTouchExpandable(true);
        return split;
    }

    private JComponent buildStatusPanel() {
        JPanel panel = new JPanel(new BorderLayout(8, 0));
        progressBar.setStringPainted(true);
        progressBar.setPreferredSize(new Dimension(300, 22));
        panel.add(statusLabel, BorderLayout.CENTER);
        panel.add(progressBar, BorderLayout.EAST);
        return panel;
    }

    private void configureTable() {
        queueTable.setAutoCreateRowSorter(true);
        queueTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        queueTable.setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
        queueTable.getColumnModel().getColumn(0).setPreferredWidth(280);
        queueTable.getColumnModel().getColumn(1).setPreferredWidth(70);
        queueTable.getColumnModel().getColumn(3).setPreferredWidth(220);
        queueTable.getColumnModel().getColumn(5).setPreferredWidth(200);
    }

    private void installQueuePopup() {
        queueTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) { maybeShow(e); }
            @Override
            public void mouseReleased(MouseEvent e) { maybeShow(e); }
            private void maybeShow(MouseEvent e) {
                if (!e.isPopupTrigger()) return;
                int viewRow = queueTable.rowAtPoint(e.getPoint());
                if (viewRow < 0) return;
                queueTable.setRowSelectionInterval(viewRow, viewRow);
                int modelRow = queueTable.convertRowIndexToModel(viewRow);
                RepositoryQueueModel.Item item = queueModel.itemAt(modelRow);
                JPopupMenu popup = new JPopupMenu();

                if (item.status == RepositoryQueueModel.Status.QUEUED
                        || item.status == RepositoryQueueModel.Status.CANCELLED
                        || item.status == RepositoryQueueModel.Status.FAILED) {
                    JMenuItem runItem = new JMenuItem("Run analysis");
                    runItem.addActionListener(ev -> runSingleItem(item));
                    popup.add(runItem);
                }
                if (activeWorker != null && !activeWorker.isDone()
                        && (item.status == RepositoryQueueModel.Status.INGESTING
                        || item.status == RepositoryQueueModel.Status.EXTRACTING
                        || item.status == RepositoryQueueModel.Status.VERIFYING)) {
                    popup.add(new JMenuItem("(analysis in progress)"));
                }

                JMenuItem removeItem = new JMenuItem("Remove");
                removeItem.addActionListener(ev -> queueModel.remove(modelRow));
                popup.add(removeItem);

                JMenu langMenu = new JMenu("Set language");
                for (Language lang : Language.values()) {
                    JMenuItem langItem = new JMenuItem(lang.toString());
                    if (item.language == lang) langItem.setFont(langItem.getFont().deriveFont(Font.BOLD));
                    langItem.addActionListener(ev -> {
                        item.language = lang;
                        queueModel.changed(item);
                        appendLog("Set language for " + item.url + " to " + lang);
                    });
                    langMenu.add(langItem);
                }
                popup.add(langMenu);

                JMenuItem detailsItem = new JMenuItem("Show details");
                detailsItem.addActionListener(ev -> showItemDetails(item));
                popup.add(detailsItem);

                if (item.output != null) {
                    JMenuItem openOutputItem = new JMenuItem("Open output folder");
                    openOutputItem.addActionListener(ev -> openItemOutput(item));
                    popup.add(openOutputItem);
                }

                if (item.status == RepositoryQueueModel.Status.COMPLETED
                        || item.status == RepositoryQueueModel.Status.VIOLATIONS
                        || item.status == RepositoryQueueModel.Status.FAILED) {
                    JMenuItem viewResultsItem = new JMenuItem("View verification results");
                    viewResultsItem.addActionListener(ev -> showResultsForRepo(item.url));
                    popup.add(viewResultsItem);
                }

                popup.show(queueTable, e.getX(), e.getY());
            }
        });
    }

    private void runSingleItem(RepositoryQueueModel.Item item) {
        List<RepositoryQueueModel.Item> items = List.of(item);
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
            progressBar.setMaximum(1);
            progressBar.setIndeterminate(false);
            setBusy(true);
            verificationModel.clear();
            activeWorker = new AnalysisWorker(items, configuration);
            activeWorker.execute();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Invalid configuration", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void showItemDetails(RepositoryQueueModel.Item item) {
        JTextArea area = new JTextArea(item.detail());
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setCaretPosition(0);
        JOptionPane.showMessageDialog(this, new JScrollPane(area),
                "Repository details", JOptionPane.INFORMATION_MESSAGE);
    }

    private void openItemOutput(RepositoryQueueModel.Item item) {
        if (item.output == null) return;
        Path dir = item.output.getParent();
        if (dir == null || !Files.isDirectory(dir)) return;
        try {
            if (Desktop.isDesktopSupported()) Desktop.getDesktop().open(dir.toFile());
        } catch (Exception ex) {
            appendLog("Cannot open output: " + ex.getMessage());
        }
    }

    private void showResultsForRepo(String url) {
        resultTabs.setSelectedIndex(TAB_VERIFICATION);
        String shortName = url.contains("/") ? url.substring(url.lastIndexOf('/') + 1) : url;
        int rowCount = verificationTable.getRowCount();
        for (int i = 0; i < rowCount; i++) {
            int modelRow = verificationTable.convertRowIndexToModel(i);
            Object val = verificationModel.getValueAt(modelRow, 0);
            if (val != null && val.toString().equalsIgnoreCase(shortName)) {
                verificationTable.setRowSelectionInterval(i, i);
                verificationTable.scrollRectToVisible(verificationTable.getCellRect(i, 0, true));
                break;
            }
        }
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
        verifyExistingButton.addActionListener(event -> verifyExistingRepos());
    }

    private void installFieldValidation() {
        installPathValidation(workspaceField, true);
        installPathValidation(outputField, true);
        installPathValidation(verifierField, true);
        installPathValidation(metamodelField, false);
    }

    private void installPathValidation(JTextField field, boolean isDirectory) {
        field.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                String text = field.getText().trim();
                if (text.isEmpty()) {
                    field.setBackground(null);
                    field.setToolTipText(null);
                    return;
                }
                Path p = Path.of(text).toAbsolutePath().normalize();
                boolean exists = isDirectory ? Files.isDirectory(p) : Files.isRegularFile(p);
                if (!exists) {
                    field.setBackground(new Color(255, 230, 230));
                    field.setToolTipText("Path does not exist: " + p);
                } else {
                    field.setBackground(new Color(230, 255, 230));
                    field.setToolTipText(p.toString());
                }
            }
        });
    }

    private void verifyExistingRepos() {
        Path outputBase = Path.of(outputField.getText().trim()).toAbsolutePath().normalize();
        if (!Files.isDirectory(outputBase)) {
            JOptionPane.showMessageDialog(this, "Output directory does not exist: " + outputBase,
                    "Cannot verify", JOptionPane.WARNING_MESSAGE);
            return;
        }
        try {
            verifyBox.setSelected(true);
            Path verifier = requiredPath(verifierField.getText(), "Verifier module");
            Path metamodel = requiredPath(metamodelField.getText(), "Alloy metamodel");
            List<Path> extractionJsons;
            try (Stream<Path> stream = Files.list(outputBase)) {
                extractionJsons = stream
                        .filter(Files::isDirectory)
                        .map(dir -> dir.resolve("extraction.json"))
                        .filter(Files::isRegularFile)
                        .sorted()
                        .toList();
            }
            if (extractionJsons.isEmpty()) {
                JOptionPane.showMessageDialog(this, "No extraction.json files found in " + outputBase,
                        "Nothing to verify", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            for (Path extraction : extractionJsons) {
                String repoName = extraction.getParent().getFileName().toString();
                queueModel.add(repoName);
            }
            RunConfiguration configuration = new RunConfiguration(
                    requiredPath(workspaceField.getText(), "Clone workspace"),
                    outputBase, 1, 17, false, true, true, verifier, metamodel);
            savePreferences();
            progressBar.setValue(0);
            progressBar.setMaximum(extractionJsons.size());
            progressBar.setIndeterminate(false);
            setBusy(true);
            verificationModel.clear();
            activeWorker = new ExistingVerificationWorker(extractionJsons, configuration);
            activeWorker.execute();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
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
        verifyExistingButton.setEnabled(!busy);
        cancelButton.setEnabled(busy);
        verifyBox.setEnabled(!busy);
        if (!busy) updateVerificationControls();
    }

    private void updateVerificationControls() {
        boolean enabled = verifyBox.isSelected() && activeWorker == null;
        verifierField.setEnabled(enabled);
        metamodelField.setEnabled(enabled);
        verifyBox.setToolTipText("Strict conformance: non-derived model relations are fixed to the Spoon extraction");
        metamodelField.setToolTipText("The metamodel (.als / .recore) is parsed for every repository run");
    }

    private JButton browseButton(JTextField target) {
        JButton button = new JButton("Browse");
        button.addActionListener(event -> {
            JFileChooser chooser = new JFileChooser(target.getText());
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                target.setText(chooser.getSelectedFile().getAbsolutePath());
                target.postActionEvent();
            }
        });
        return button;
    }

    private JButton fileBrowseButton(JTextField target) {
        JButton button = new JButton("Browse");
        button.addActionListener(event -> {
            JFileChooser chooser = new JFileChooser(target.getText());
            chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
            chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                    "Metamodel (.als, .recore)", "als", "recore"));
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                target.setText(chooser.getSelectedFile().getAbsolutePath());
                target.postActionEvent();
            }
        });
        return button;
    }

    private void openOutputFolder() {
        try {
            Path output = Path.of(outputField.getText()).toAbsolutePath().normalize();
            if (!Files.isDirectory(output)) {
                JOptionPane.showMessageDialog(this, "Output folder does not exist: " + output,
                        "Cannot open", JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (!Desktop.isDesktopSupported()) {
                JOptionPane.showMessageDialog(this, "Desktop integration is unavailable",
                        "Cannot open", JOptionPane.WARNING_MESSAGE);
                return;
            }
            Desktop.getDesktop().open(output.toFile());
        } catch (Exception ex) {
            appendLog("Cannot open output: " + ex.getMessage());
        }
    }

    private void appendLog(String message) {
        logArea.append(message + System.lineSeparator());
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    private void updateVerificationTabBadge() {
        int count = verificationModel.getRowCount();
        String title = count > 0 ? "Verification results (" + count + ")" : "Verification results";
        resultTabs.setTitleAt(TAB_VERIFICATION, title);
    }

    private void loadPreferences() {
        workspaceField.setText(preferences.get(PREF_WORKSPACE, DEFAULT_WORKSPACE));
        outputField.setText(preferences.get(PREF_OUTPUT, DEFAULT_OUTPUT));
        verifierField.setText(staleToDefault(PREF_VERIFIER, DEFAULT_VERIFIER, true));
        metamodelField.setText(staleToDefault(PREF_METAMODEL, DEFAULT_METAMODEL, false));
        verifyBox.setSelected(preferences.getBoolean(PREF_VERIFY, false));
        includeTestsBox.setSelected(preferences.getBoolean(PREF_INCLUDE_TESTS, false));
        reuseBox.setSelected(preferences.getBoolean(PREF_REUSE, true));
    }

    private String staleToDefault(String key, String defaultValue, boolean isDirectory) {
        String saved = preferences.get(key, defaultValue);
        Path savedPath = Path.of(saved).toAbsolutePath().normalize();
        boolean exists = isDirectory ? Files.isDirectory(savedPath) : Files.isRegularFile(savedPath);
        if (!exists) {
            return defaultValue;
        }
        return saved;
    }

    private void savePreferences() {
        preferences.put(PREF_WORKSPACE, workspaceField.getText().trim());
        preferences.put(PREF_OUTPUT, outputField.getText().trim());
        preferences.put(PREF_VERIFIER, verifierField.getText().trim());
        preferences.put(PREF_METAMODEL, metamodelField.getText().trim());
        preferences.putBoolean(PREF_VERIFY, verifyBox.isSelected());
        preferences.putBoolean(PREF_INCLUDE_TESTS, includeTestsBox.isSelected());
        preferences.putBoolean(PREF_REUSE, reuseBox.isSelected());
    }

    private void flushPreferences() {
        try {
            preferences.flush();
        } catch (BackingStoreException ignored) { }
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

    static Language detectLanguage(Path repoDir) {
        try {
            List<Path> files = Files.walk(repoDir, 4)
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase();
                        return name.endsWith(".py") || name.endsWith(".java") || name.endsWith(".cpp");
                    })
                    .toList();
            long pyCount = files.stream().filter(p -> p.toString().endsWith(".py")).count();
            long javaCount = files.stream().filter(p -> p.toString().endsWith(".java")).count();
            long cppCount = files.stream().filter(p -> {
                String n = p.toString().toLowerCase();
                return n.endsWith(".cpp") || n.endsWith(".cc") || n.endsWith(".cxx")
                        || n.endsWith(".h") || n.endsWith(".hpp");
            }).count();
            if (pyCount > javaCount && pyCount > cppCount && pyCount > 0) return Language.PYTHON;
            if (cppCount > javaCount && cppCount > 0) return Language.CPP;
            if (javaCount > 0) return Language.JAVA;
        } catch (Exception ignored) { }
        return Language.JAVA;
    }

    private final class AnalysisWorker extends SwingWorker<Void, UiEvent> {
        private final List<RepositoryQueueModel.Item> items;
        private final RunConfiguration configuration;
        private final RepositoryIngestionService ingestion = new JGitHubRepositoryIngestionService();
        private final JavaExtractionService javaExtraction = new SpoonJavaExtractionService();
        private final JavaExtractionService pythonExtraction = new PythonExtractionService();
        private final JavaExtractionService cppExtraction = new CppExtractionService();
        private final ExtractionJsonWriter writer = new ExtractionJsonWriter();
        private final AlloyInEcoreVerificationService verification = new AlloyInEcoreVerificationService();
        private volatile int completed;

        private AnalysisWorker(List<RepositoryQueueModel.Item> items, RunConfiguration configuration) {
            this.items = items;
            this.configuration = configuration;
        }

        private JavaExtractionService selectExtractor(Language lang) {
            return switch (lang) {
                case PYTHON -> pythonExtraction;
                case CPP -> cppExtraction;
                default -> javaExtraction;
            };
        }

        private String extractorName(Language lang) {
            return switch (lang) {
                case PYTHON -> "Python AST";
                case CPP -> "Clang";
                default -> "Spoon";
            };
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

                    Language lang = item.language != Language.JAVA ? item.language
                            : AnalysisFrame.detectLanguage(repository.directory());
                    item.language = lang;
                    JavaExtractionService extractor = selectExtractor(lang);
                    String extractorName = extractorName(lang);

                    publish(new UiEvent(item, RepositoryQueueModel.Status.EXTRACTING,
                            "Building " + extractorName + " model", null, null,
                            (repository.reused() ? "Reusing " : "Cloned ") + request.coordinate()
                                    + " at " + repository.revision()
                                    + " [" + lang + "]", null));

                    Path output = configuration.output()
                            .resolve(request.owner() + "__" + request.name()).resolve("extraction.json");
                    AnalysisCache cache = AnalysisCache.load(output.getParent());
                    String extractionKey = AnalysisCache.extractionKey(repository.directory(), repository.revision(),
                            configuration.compliance(), configuration.includeTests());
                    int typeCount;
                    if (cache.hasExtraction(extractionKey, output)) {
                        typeCount = cache.typeCount();
                        publish(new UiEvent(item, RepositoryQueueModel.Status.EXTRACTING,
                                "Reusing cached " + extractorName + " extraction", typeCount, output,
                                "Cache hit for " + request.coordinate() + ": " + extractorName + " extraction skipped", null));
                    } else {
                        var result = extractor.extract(
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
                    updateVerificationTabBadge();
                }
                statusLabel.setText(event.activity());
            }
            int c = completed;
            int total = items.size();
            progressBar.setValue(Math.min(c, total));
            progressBar.setString(c + " / " + total);
            progressBar.setIndeterminate(c == 0 && total > 0);
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
                progressBar.setIndeterminate(false);
                setBusy(false);
                activeWorker = null;
            }
        }
    }

    private final class ExistingVerificationWorker extends SwingWorker<Void, UiEvent> {
        private final List<Path> extractionJsons;
        private final RunConfiguration configuration;
        private final AlloyInEcoreVerificationService verification = new AlloyInEcoreVerificationService();
        private volatile int completed;

        private ExistingVerificationWorker(List<Path> extractionJsons, RunConfiguration configuration) {
            this.extractionJsons = extractionJsons;
            this.configuration = configuration;
        }

        @Override
        protected Void doInBackground() {
            for (Path extraction : extractionJsons) {
                if (isCancelled()) break;
                String repoName = extraction.getParent().getFileName().toString();
                try {
                    VerificationOutcome outcome = verification.verify(new VerificationRequest(
                                    configuration.verifierHome(), configuration.metamodel(),
                                    extraction, extraction.getParent().resolve("verification")),
                            event -> { }, this::isCancelled);
                    completed++;
                    RepositoryQueueModel.Status st = outcome.status() == VerificationOutcome.Status.UNSAT
                            ? RepositoryQueueModel.Status.VIOLATIONS : RepositoryQueueModel.Status.COMPLETED;
                    publish(new UiEvent(null, st,
                            outcome.status() + " — " + outcome.violations().size() + " violation(s)",
                            null, null, "Verified " + repoName + ": " + outcome.status(), outcome));
                } catch (Exception ex) {
                    if (isCancelled()) break;
                    completed++;
                    publish(new UiEvent(null, RepositoryQueueModel.Status.FAILED,
                            ex.getMessage(), null, null, "Failed " + repoName + ": " + ex.getMessage(), null));
                }
            }
            return null;
        }

        @Override
        protected void process(List<UiEvent> chunks) {
            for (UiEvent event : chunks) {
                if (event.log() != null) appendLog(event.log());
                if (event.verification() != null) {
                    verificationModel.add(event.log() != null
                            ? event.log().replaceFirst("^Verified ", "").replaceFirst(":.*", "")
                            : "unknown", event.verification());
                    updateVerificationTabBadge();
                }
                if (!resultTabs.isShowing() || resultTabs.getSelectedIndex() == TAB_ACTIVITY) {
                    statusLabel.setText(event.activity());
                }
            }
            int total = extractionJsons.size();
            int c = completed;
            progressBar.setValue(Math.min(c, total));
            progressBar.setString(c + " / " + total);
        }

        @Override
        protected void done() {
            try {
                get();
                statusLabel.setText(isCancelled() ? "Cancelled" : "Finished verifying existing repos");
            } catch (CancellationException ex) {
                statusLabel.setText("Cancelled");
            } catch (Exception ex) {
                statusLabel.setText("Unexpected failure");
                appendLog("Unexpected worker failure: " + ex.getMessage());
            } finally {
                progressBar.setIndeterminate(false);
                setBusy(false);
                activeWorker = null;
            }
        }
    }
}
