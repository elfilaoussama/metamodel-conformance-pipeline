package com.javapipeline.desktop;

import com.javapipeline.core.ProgressEvent;
import com.javapipeline.core.search.*;
import com.javapipeline.github.search.GitHubRestRepositorySearchService;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import java.util.prefs.Preferences;

final class GitHubSearchDialog extends JDialog {
    private final Consumer<List<GitHubRepositorySummary>> addToQueue;
    private final GitHubSearchTableModel resultsModel = new GitHubSearchTableModel();
    private final JTable resultsTable = new JTable(resultsModel);

    private final JTextField keywordsField = new JTextField();
    private final JTextField languageField = new JTextField("Java");
    private final JTextField ownerField = new JTextField();
    private final JTextField topicField = new JTextField();
    private final JTextField licenseField = new JTextField();
    private final JTextField minStarsField = new JTextField();
    private final JTextField maxStarsField = new JTextField();
    private final JTextField minForksField = new JTextField();
    private final JTextField maxForksField = new JTextField();
    private final JTextField minSizeField = new JTextField();
    private final JTextField maxSizeField = new JTextField();
    private final JTextField createdAfterField = new JTextField();
    private final JTextField pushedAfterField = new JTextField();
    private final JComboBox<GitHubSearchCriteria.ForkMode> forkModeBox =
            new JComboBox<>(GitHubSearchCriteria.ForkMode.values());
    private final JComboBox<GitHubSearchCriteria.ArchiveMode> archiveModeBox =
            new JComboBox<>(GitHubSearchCriteria.ArchiveMode.values());
    private final JComboBox<GitHubSearchCriteria.Sort> sortBox =
            new JComboBox<>(GitHubSearchCriteria.Sort.values());
    private final JComboBox<GitHubSearchCriteria.Order> orderBox =
            new JComboBox<>(GitHubSearchCriteria.Order.values());
    private final JSpinner limitSpinner = new JSpinner(new SpinnerNumberModel(50, 1, 1000, 10));
    private final JPasswordField tokenField = new JPasswordField();

    private final JButton searchButton = new JButton("Search GitHub");
    private final JButton cancelButton = new JButton("Cancel search");
    private final JButton addButton = new JButton("Add selected to queue");
    private final JLabel statusLabel = new JLabel("Enter keywords or filters, then search.");
    private final JProgressBar progressBar = new JProgressBar();
    private final JButton previousPageButton = new JButton("Previous");
    private final JButton nextPageButton = new JButton("Next");
    private final JLabel pageLabel = new JLabel("Page 1 of 1");
    private final JComboBox<Integer> pageSizeBox = new JComboBox<>(new Integer[]{25, 50, 100});
    private final JTextArea detailsArea = new JTextArea(6, 50);
    private final Preferences preferences = Preferences.userNodeForPackage(GitHubSearchDialog.class);
    private SearchWorker worker;

    GitHubSearchDialog(Window owner, Consumer<List<GitHubRepositorySummary>> addToQueue) {
        super(owner, "Search GitHub repositories", ModalityType.APPLICATION_MODAL);
        this.addToQueue = addToQueue;
        setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(1100, 720));
        setSize(1250, 820);
        setLocationRelativeTo(owner);

        JPanel content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(new EmptyBorder(10, 10, 10, 10));
        setContentPane(content);
        content.add(buildSearchArea(), BorderLayout.NORTH);
        content.add(buildResultsArea(), BorderLayout.CENTER);
        content.add(buildStatusArea(), BorderLayout.SOUTH);

        wireActions();
        configureTable();
        loadPreferences();
        setSearching(false);
    }

    private JComponent buildSearchArea() {
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Search", basicPanel());
        tabs.addTab("Advanced", advancedPanel());
        tabs.addTab("Authentication", authenticationPanel());

        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.add(searchButton);
        actions.add(cancelButton);

        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.add(tabs, BorderLayout.CENTER);
        panel.add(actions, BorderLayout.SOUTH);
        return panel;
    }

    private JComponent basicPanel() {
        JPanel panel = formPanel();
        int row = 0;
        addField(panel, row++, "Keywords / GitHub syntax", keywordsField,
                "Searched in repository name, description, and README");
        addField(panel, row++, "Primary language", languageField, "For this pipeline, Java is recommended");
        addField(panel, row++, "Owner", ownerField, "Username, or prefix an organization with org:");
        addField(panel, row++, "Topic", topicField, "Example: static-analysis");
        addField(panel, row++, "License", licenseField, "SPDX keyword, for example apache-2.0 or mit");

        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        controls.add(new JLabel("Maximum results"));
        controls.add(limitSpinner);
        controls.add(new JLabel("Sort"));
        controls.add(sortBox);
        controls.add(new JLabel("Order"));
        controls.add(orderBox);
        addField(panel, row, "Result options", controls, "GitHub returns at most 1,000 search results");

        JTextArea limits = new JTextArea(
                "GitHub limits each API page to 100 repositories and exposes only the first 1,000 matches. "
                        + "Search requests use a separate rate limit. Very broad or expensive searches may be incomplete "
                        + "or time out; add owner, language, stars, topic, or date filters to narrow them.");
        limits.setEditable(false);
        limits.setLineWrap(true);
        limits.setWrapStyleWord(true);
        limits.setOpaque(false);
        addField(panel, row + 1, "GitHub limits", limits, "The status bar reports rate-limit and incomplete-result information");
        return panel;
    }

    private JComponent advancedPanel() {
        JPanel panel = formPanel();
        int row = 0;
        addRange(panel, row++, "Stars", minStarsField, maxStarsField);
        addRange(panel, row++, "Forks", minForksField, maxForksField);
        addRange(panel, row++, "Repository size (KB)", minSizeField, maxSizeField);
        addField(panel, row++, "Created on/after", createdAfterField, "Date format: yyyy-MM-dd");
        addField(panel, row++, "Pushed on/after", pushedAfterField, "Date format: yyyy-MM-dd");

        JPanel modes = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        modes.add(new JLabel("Forks"));
        modes.add(forkModeBox);
        modes.add(new JLabel("Archived"));
        modes.add(archiveModeBox);
        addField(panel, row, "Repository state", modes, "Exclude, include, or select only matching repositories");
        return panel;
    }

    private JComponent authenticationPanel() {
        JPanel panel = formPanel();
        addField(panel, 0, "Access token", tokenField,
                "Optional for public repositories; enables private access and higher search rate limits");
        JTextArea note = new JTextArea(
                "Use a fine-grained personal access token with only the repository access you need. "
                        + "The token is held only for the current request and is never written to preferences, logs, or output.");
        note.setEditable(false);
        note.setLineWrap(true);
        note.setWrapStyleWord(true);
        note.setOpaque(false);
        addField(panel, 1, "Security", note, "GitHub password authentication is not supported by the REST API");
        return panel;
    }

    private JPanel formPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(new EmptyBorder(6, 6, 6, 6));
        return panel;
    }

    private void addRange(JPanel panel, int row, String label, JTextField minimum, JTextField maximum) {
        JPanel range = new JPanel(new GridLayout(1, 4, 6, 0));
        range.add(new JLabel("Minimum"));
        range.add(minimum);
        range.add(new JLabel("Maximum"));
        range.add(maximum);
        addField(panel, row, label, range, "Leave either bound empty when it is not needed");
    }

    private void addField(JPanel panel, int row, String label, JComponent field, String hint) {
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 4, 3, 4);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridx = 0; c.gridy = row; c.weightx = 0;
        panel.add(new JLabel(label), c);
        c.gridx = 1; c.weightx = 1;
        panel.add(field, c);
        c.gridx = 2; c.weightx = 0.25;
        JLabel hintLabel = new JLabel(hint);
        hintLabel.setForeground(UIManager.getColor("Label.disabledForeground"));
        panel.add(hintLabel, c);
    }

    private JComponent buildResultsArea() {
        JPanel selection = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        JButton all = new JButton("Select all");
        JButton none = new JButton("Select none");
        JButton invert = new JButton("Invert selection");
        all.addActionListener(event -> resultsModel.selectAll(true));
        none.addActionListener(event -> resultsModel.selectAll(false));
        invert.addActionListener(event -> resultsModel.invertSelection());
        selection.add(all);
        selection.add(none);
        selection.add(invert);
        selection.add(addButton);

        JPanel paging = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        paging.add(new JLabel("Rows per page"));
        paging.add(pageSizeBox);
        paging.add(previousPageButton);
        paging.add(pageLabel);
        paging.add(nextPageButton);

        JPanel toolbar = new JPanel(new BorderLayout());
        toolbar.add(selection, BorderLayout.WEST);
        toolbar.add(paging, BorderLayout.EAST);

        detailsArea.setEditable(false);
        detailsArea.setLineWrap(true);
        detailsArea.setWrapStyleWord(true);
        detailsArea.setBorder(new EmptyBorder(5, 5, 5, 5));
        detailsArea.setText("Select a repository to see its details.");

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT,
                new JScrollPane(resultsTable), new JScrollPane(detailsArea));
        split.setResizeWeight(0.78);
        split.setDividerLocation(360);

        JPanel panel = new JPanel(new BorderLayout(5, 5));
        panel.setBorder(BorderFactory.createTitledBorder("Search results"));
        panel.add(toolbar, BorderLayout.NORTH);
        panel.add(split, BorderLayout.CENTER);
        return panel;
    }

    private JComponent buildStatusArea() {
        progressBar.setStringPainted(true);
        progressBar.setPreferredSize(new Dimension(300, 20));
        JButton close = new JButton("Close");
        close.addActionListener(event -> dispose());
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        left.add(addButton);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        right.add(progressBar);
        right.add(close);
        JPanel panel = new JPanel(new BorderLayout(8, 0));
        panel.add(left, BorderLayout.WEST);
        panel.add(statusLabel, BorderLayout.CENTER);
        panel.add(right, BorderLayout.EAST);
        return panel;
    }

    private void configureTable() {
        resultsTable.setAutoCreateRowSorter(true);
        resultsTable.setAutoResizeMode(JTable.AUTO_RESIZE_SUBSEQUENT_COLUMNS);
        int[] widths = {45, 200, 65, 55, 70, 70, 80, 60};
        for (int index = 0; index < widths.length && index < resultsTable.getColumnCount(); index++) {
            resultsTable.getColumnModel().getColumn(index).setPreferredWidth(widths[index]);
        }
        resultsModel.addTableModelListener(event ->
                updateResultControls());
        resultsTable.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) showSelectedRepositoryDetails();
        });
    }

    private void wireActions() {
        searchButton.addActionListener(event -> startSearch());
        previousPageButton.addActionListener(event -> {
            resultsModel.previousPage();
            selectFirstVisibleRow();
        });
        nextPageButton.addActionListener(event -> {
            resultsModel.nextPage();
            selectFirstVisibleRow();
        });
        pageSizeBox.addActionListener(event -> {
            resultsModel.setPageSize((Integer) pageSizeBox.getSelectedItem());
            selectFirstVisibleRow();
        });
        cancelButton.addActionListener(event -> {
            if (worker != null) worker.cancel(true);
        });
        addButton.addActionListener(event -> {
            List<GitHubRepositorySummary> selected = resultsModel.selectedRepositories();
            if (selected.isEmpty()) {
                JOptionPane.showMessageDialog(this, "Select at least one repository.",
                        "No repositories selected", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            addToQueue.accept(selected);
            statusLabel.setText("Added " + selected.size() + " repositories to the ingestion queue.");
        });
    }

    private void startSearch() {
        try {
            GitHubSearchCriteria criteria = criteria();
            char[] token = tokenField.getPassword();
            resultsModel.setRepositories(List.of());
            setSearching(true);
            statusLabel.setText("Searching GitHub...");
            progressBar.setMinimum(0);
            progressBar.setMaximum(criteria.resultLimit());
            progressBar.setValue(0);
            progressBar.setIndeterminate(true);
            savePreferences();
            worker = new SearchWorker(criteria, token);
            worker.execute();
        } catch (IllegalArgumentException ex) {
            JOptionPane.showMessageDialog(this, ex.getMessage(), "Invalid search", JOptionPane.ERROR_MESSAGE);
        }
    }

    private GitHubSearchCriteria criteria() {
        return new GitHubSearchCriteria(
                keywordsField.getText(), languageField.getText(), ownerField.getText(),
                topicField.getText(), licenseField.getText(),
                integer(minStarsField, "minimum stars"), integer(maxStarsField, "maximum stars"),
                integer(minForksField, "minimum forks"), integer(maxForksField, "maximum forks"),
                integer(minSizeField, "minimum size"), integer(maxSizeField, "maximum size"),
                date(createdAfterField, "created-after date"), date(pushedAfterField, "pushed-after date"),
                (GitHubSearchCriteria.ForkMode) forkModeBox.getSelectedItem(),
                (GitHubSearchCriteria.ArchiveMode) archiveModeBox.getSelectedItem(),
                (GitHubSearchCriteria.Sort) sortBox.getSelectedItem(),
                (GitHubSearchCriteria.Order) orderBox.getSelectedItem(),
                (Integer) limitSpinner.getValue());
    }

    private static Integer integer(JTextField field, String label) {
        String value = field.getText().trim();
        if (value.isEmpty()) return null;
        try { return Integer.valueOf(value); }
        catch (NumberFormatException ex) { throw new IllegalArgumentException("Invalid " + label + ": " + value); }
    }

    private static LocalDate date(JTextField field, String label) {
        String value = field.getText().trim();
        if (value.isEmpty()) return null;
        try { return LocalDate.parse(value); }
        catch (DateTimeParseException ex) { throw new IllegalArgumentException("Invalid " + label + "; use yyyy-MM-dd"); }
    }

    private void setSearching(boolean searching) {
        searchButton.setEnabled(!searching);
        cancelButton.setEnabled(searching);
        addButton.setEnabled(!searching && resultsModel.getRowCount() > 0);
        previousPageButton.setEnabled(!searching && resultsModel.hasPreviousPage());
        nextPageButton.setEnabled(!searching && resultsModel.hasNextPage());
        pageSizeBox.setEnabled(!searching);
    }

    private void updateResultControls() {
        addButton.setText("Add selected to queue (" + resultsModel.selectedCount() + ")");
        addButton.setEnabled(worker == null && resultsModel.totalCount() > 0);
        pageLabel.setText("Page " + resultsModel.currentPage() + " of " + resultsModel.pageCount()
                + "  (" + resultsModel.totalCount() + " loaded)");
        previousPageButton.setEnabled(worker == null && resultsModel.hasPreviousPage());
        nextPageButton.setEnabled(worker == null && resultsModel.hasNextPage());
    }

    private void selectFirstVisibleRow() {
        if (resultsModel.getRowCount() > 0) resultsTable.setRowSelectionInterval(0, 0);
        else detailsArea.setText("No repositories on this page.");
        updateResultControls();
    }

    private void showSelectedRepositoryDetails() {
        int viewRow = resultsTable.getSelectedRow();
        if (viewRow < 0) {
            detailsArea.setText("Select a repository to see its details.");
            return;
        }
        GitHubRepositorySummary repository = resultsModel.repositoryAt(resultsTable.convertRowIndexToModel(viewRow));
        detailsArea.setText("Repository: " + repository.fullName()
                + "\nDescription: " + SwingUtils.blank(repository.description(), "No description")
                + "\nWeb: " + repository.htmlUrl()
                + "\nClone: " + repository.cloneUrl()
                + "\nDefault branch: " + SwingUtils.blank(repository.defaultBranch(), "Unknown")
                + " | Language: " + SwingUtils.blank(repository.language(), "Unknown")
                + " | License: " + SwingUtils.blank(repository.license(), "Not declared")
                + "\nStars: " + repository.stars() + " | Forks: " + repository.forks()
                + " | Size: " + repository.sizeKb() + " KB"
                + " | Fork: " + repository.fork() + " | Archived: " + repository.archived()
                + "\nUpdated: " + repository.updatedAt() + " | Last push: " + repository.pushedAt());
        detailsArea.setCaretPosition(0);
    }

    private void loadPreferences() {
        keywordsField.setText(preferences.get("ghs.keywords", ""));
        languageField.setText(preferences.get("ghs.language", "Java"));
        ownerField.setText(preferences.get("ghs.owner", ""));
        topicField.setText(preferences.get("ghs.topic", ""));
        licenseField.setText(preferences.get("ghs.license", ""));
        minStarsField.setText(preferences.get("ghs.minStars", ""));
        maxStarsField.setText(preferences.get("ghs.maxStars", ""));
        limitSpinner.setValue(preferences.getInt("ghs.limit", 50));
        pageSizeBox.setSelectedItem(preferences.getInt("ghs.pageSize", 25));
        String sort = preferences.get("ghs.sort", "STARS");
        for (int i = 0; i < sortBox.getItemCount(); i++) {
            if (sortBox.getItemAt(i).name().equals(sort)) {
                sortBox.setSelectedIndex(i);
                break;
            }
        }
        String order = preferences.get("ghs.order", "DESC");
        for (int i = 0; i < orderBox.getItemCount(); i++) {
            if (orderBox.getItemAt(i).name().equals(order)) {
                orderBox.setSelectedIndex(i);
                break;
            }
        }
    }

    private void savePreferences() {
        preferences.put("ghs.keywords", keywordsField.getText().trim());
        preferences.put("ghs.language", languageField.getText().trim());
        preferences.put("ghs.owner", ownerField.getText().trim());
        preferences.put("ghs.topic", topicField.getText().trim());
        preferences.put("ghs.license", licenseField.getText().trim());
        preferences.put("ghs.minStars", minStarsField.getText().trim());
        preferences.put("ghs.maxStars", maxStarsField.getText().trim());
        preferences.putInt("ghs.limit", (Integer) limitSpinner.getValue());
        preferences.putInt("ghs.pageSize", (Integer) pageSizeBox.getSelectedItem());
        preferences.put("ghs.sort", ((GitHubSearchCriteria.Sort) sortBox.getSelectedItem()).name());
        preferences.put("ghs.order", ((GitHubSearchCriteria.Order) orderBox.getSelectedItem()).name());
        try { preferences.flush(); } catch (Exception ignored) { }
    }

    @Override
    public void dispose() {
        if (worker != null) worker.cancel(true);
        tokenField.setText("");
        super.dispose();
    }

    private final class SearchWorker extends SwingWorker<GitHubSearchResponse, ProgressEvent> {
        private final GitHubSearchCriteria criteria;
        private final char[] token;

        private SearchWorker(GitHubSearchCriteria criteria, char[] token) {
            this.criteria = criteria;
            this.token = token;
        }

        @Override
        protected GitHubSearchResponse doInBackground() throws Exception {
            try {
                return new GitHubRestRepositorySearchService().search(
                        criteria, token, this::publish, this::isCancelled);
            } finally {
                Arrays.fill(token, '\0');
            }
        }

        @Override
        protected void process(List<ProgressEvent> chunks) {
            if (chunks.isEmpty()) return;
            ProgressEvent event = chunks.get(chunks.size() - 1);
            statusLabel.setText(event.message());
            if (event.total() > 0) {
                progressBar.setIndeterminate(false);
                progressBar.setMaximum((int) Math.min(Integer.MAX_VALUE, event.total()));
                progressBar.setValue((int) Math.min(Integer.MAX_VALUE, event.completed()));
                progressBar.setString(event.completed() + " / " + event.total());
            }
        }

        @Override
        protected void done() {
            try {
                GitHubSearchResponse response = get();
                resultsModel.setRepositories(response.repositories());
                selectFirstVisibleRow();
                String status = "Showing " + response.repositories().size() + " of " + response.totalCount() + " matches";
                if (response.incomplete()) status += " (GitHub marked results incomplete)";
                if (response.rateLimitRemaining() != null) {
                    status += "; search requests remaining: " + response.rateLimitRemaining();
                    if (response.rateLimitReset() != null) {
                        status += " until " + response.rateLimitReset().atZone(ZoneId.systemDefault()).toLocalTime();
                    }
                }
                statusLabel.setText(status);
                progressBar.setIndeterminate(false);
                progressBar.setValue(response.repositories().size());
                progressBar.setString(response.repositories().size() + " results");
            } catch (CancellationException ex) {
                statusLabel.setText("Search cancelled.");
            } catch (Exception ex) {
                Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                statusLabel.setText("Search failed: " + cause.getMessage());
                JOptionPane.showMessageDialog(GitHubSearchDialog.this, cause.getMessage(),
                        "GitHub search failed", JOptionPane.ERROR_MESSAGE);
            } finally {
                worker = null;
                setSearching(false);
                updateResultControls();
            }
        }
    }
}
