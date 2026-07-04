package com.javapipeline.desktop;

import com.javapipeline.core.search.GitHubRepositorySummary;

import javax.swing.table.AbstractTableModel;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

final class GitHubSearchTableModel extends AbstractTableModel {
    private static final String[] COLUMNS = {
            "Add", "Repository", "Stars", "Forks", "Language", "License", "Updated", "Archived", "Description"
    };
    private static final DateTimeFormatter DATE = DateTimeFormatter.ISO_LOCAL_DATE.withZone(ZoneOffset.UTC);
    private final List<Row> rows = new ArrayList<>();
    private int pageSize = 25;
    private int currentPage;

    private static final class Row {
        boolean selected = true;
        final GitHubRepositorySummary repository;
        private Row(GitHubRepositorySummary repository) { this.repository = repository; }
    }

    void setRepositories(List<GitHubRepositorySummary> repositories) {
        rows.clear();
        repositories.stream().map(Row::new).forEach(rows::add);
        currentPage = 0;
        fireTableDataChanged();
    }

    void setPageSize(int pageSize) {
        this.pageSize = Math.max(1, pageSize);
        currentPage = Math.min(currentPage, pageCount() - 1);
        fireTableDataChanged();
    }

    void previousPage() {
        if (currentPage > 0) {
            currentPage--;
            fireTableDataChanged();
        }
    }

    void nextPage() {
        if (currentPage + 1 < pageCount()) {
            currentPage++;
            fireTableDataChanged();
        }
    }

    int currentPage() { return currentPage + 1; }
    int pageCount() { return Math.max(1, (rows.size() + pageSize - 1) / pageSize); }
    int totalCount() { return rows.size(); }
    boolean hasPreviousPage() { return currentPage > 0; }
    boolean hasNextPage() { return currentPage + 1 < pageCount(); }
    GitHubRepositorySummary repositoryAt(int visibleRow) { return rowAt(visibleRow).repository; }

    private Row rowAt(int visibleRow) {
        return rows.get(currentPage * pageSize + visibleRow);
    }

    void selectAll(boolean selected) {
        rows.forEach(row -> row.selected = selected);
        fireTableDataChanged();
    }

    void invertSelection() {
        rows.forEach(row -> row.selected = !row.selected);
        fireTableDataChanged();
    }

    List<String> selectedCloneUrls() {
        return rows.stream().filter(row -> row.selected)
                .map(row -> row.repository.cloneUrl()).filter(url -> !url.isBlank()).toList();
    }

    int selectedCount() {
        return (int) rows.stream().filter(row -> row.selected).count();
    }

    @Override public int getRowCount() {
        return Math.min(pageSize, Math.max(0, rows.size() - currentPage * pageSize));
    }
    @Override public int getColumnCount() { return COLUMNS.length; }
    @Override public String getColumnName(int column) { return COLUMNS[column]; }
    @Override public Class<?> getColumnClass(int column) { return column == 0 ? Boolean.class : Object.class; }
    @Override public boolean isCellEditable(int row, int column) { return column == 0; }

    @Override
    public void setValueAt(Object value, int row, int column) {
        if (column == 0) {
            rowAt(row).selected = Boolean.TRUE.equals(value);
            fireTableCellUpdated(row, column);
        }
    }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        Row row = rowAt(rowIndex);
        GitHubRepositorySummary repository = row.repository;
        return switch (columnIndex) {
            case 0 -> row.selected;
            case 1 -> repository.fullName();
            case 2 -> repository.stars();
            case 3 -> repository.forks();
            case 4 -> repository.language();
            case 5 -> repository.license();
            case 6 -> repository.updatedAt() == null ? "" : DATE.format(repository.updatedAt());
            case 7 -> repository.archived();
            case 8 -> repository.description();
            default -> "";
        };
    }
}
