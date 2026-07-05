package com.javapipeline.desktop;

import com.javapipeline.verification.VerificationOutcome;

import javax.swing.table.AbstractTableModel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

final class VerificationTableModel extends AbstractTableModel {
    private static final String[] COLUMNS = {
            "Repository", "Result", "Constraint", "Line", "Description", "CSV report"
    };
    private final List<Row> rows = new ArrayList<>();

    void clear() {
        rows.clear();
        fireTableDataChanged();
    }

    void add(String repository, VerificationOutcome outcome) {
        if (outcome.violations().isEmpty()) {
            rows.add(new Row(repository, outcome.status().name(), "", null,
                    outcome.status() == VerificationOutcome.Status.SAT
                            ? "All metamodel constraints hold" : "No detailed constraint mapping available",
                    outcome.csvReport()));
        } else {
            for (VerificationOutcome.Violation violation : outcome.violations()) {
                rows.add(new Row(repository, outcome.status().name(),
                        SwingUtils.blank(violation.invariantName(), "[unnamed constraint]"), violation.line(),
                        SwingUtils.blank(violation.description(), violation.formula()), outcome.csvReport()));
            }
        }
        fireTableDataChanged();
    }

    List<Row> allRows() { return List.copyOf(rows); }

    List<Row> filterByRepository(String repoName) {
        if (repoName == null || repoName.isBlank()) return List.copyOf(rows);
        return rows.stream()
                .filter(r -> r.repository.equalsIgnoreCase(repoName))
                .toList();
    }

    List<String> distinctRepositories() {
        return rows.stream()
                .map(r -> r.repository)
                .distinct()
                .sorted(String.CASE_INSENSITIVE_ORDER)
                .toList();
    }

    @Override public int getRowCount() { return rows.size(); }
    @Override public int getColumnCount() { return COLUMNS.length; }
    @Override public String getColumnName(int column) { return COLUMNS[column]; }
    @Override public Object getValueAt(int row, int column) {
        Row value = rows.get(row);
        return switch (column) {
            case 0 -> value.repository;
            case 1 -> value.result;
            case 2 -> value.constraint;
            case 3 -> value.line == null ? "" : value.line;
            case 4 -> value.description;
            case 5 -> value.csv == null ? "" : value.csv;
            default -> "";
        };
    }

    record Row(String repository, String result, String constraint,
               Integer line, String description, Path csv) { }
}
