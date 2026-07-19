package com.javapipeline.desktop;

import com.javapipeline.core.Language;

import javax.swing.table.AbstractTableModel;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

final class RepositoryQueueModel extends AbstractTableModel {
    enum Status { QUEUED, INGESTING, EXTRACTING, VERIFYING, COMPLETED, VIOLATIONS, FAILED, CANCELLED }

    static final class Item {
        final String url;
        Status status = Status.QUEUED;
        String activity = "Waiting";
        Integer typeCount;
        Path output;
        Language language = Language.JAVA;

        Item(String url) { this.url = url; }

        String detail() {
            StringBuilder sb = new StringBuilder();
            sb.append("Repository: ").append(url).append('\n');
            sb.append("Status: ").append(status).append('\n');
            sb.append("Activity: ").append(SwingUtils.blank(activity, "Waiting")).append('\n');
            sb.append("Language: ").append(language).append('\n');
            if (typeCount != null) sb.append("Types extracted: ").append(typeCount).append('\n');
            if (output != null) sb.append("Output: ").append(output).append('\n');
            return sb.toString();
        }
    }

    private static final String[] COLUMNS = {"Repository", "Language", "Status", "Activity", "Types", "Output"};
    private final List<Item> items = new ArrayList<>();

    void add(String url) {
        if (items.stream().anyMatch(item -> item.url.equalsIgnoreCase(url))) return;
        int index = items.size();
        items.add(new Item(url));
        fireTableRowsInserted(index, index);
    }

    void remove(int modelRow) {
        if (modelRow < 0 || modelRow >= items.size()) return;
        items.remove(modelRow);
        fireTableRowsDeleted(modelRow, modelRow);
    }

    void clear() {
        if (items.isEmpty()) return;
        int last = items.size() - 1;
        items.clear();
        fireTableRowsDeleted(0, last);
    }

    Item get(String url) {
        return items.stream().filter(item -> item.url.equalsIgnoreCase(url)).findFirst().orElse(null);
    }

    Item itemAt(int modelRow) {
        if (modelRow < 0 || modelRow >= items.size()) return null;
        return items.get(modelRow);
    }

    List<Item> snapshot() { return List.copyOf(items); }

    void changed(Item item) {
        int index = items.indexOf(item);
        if (index >= 0) fireTableRowsUpdated(index, index);
    }

    @Override public int getRowCount() { return items.size(); }
    @Override public int getColumnCount() { return COLUMNS.length; }
    @Override public String getColumnName(int column) { return COLUMNS[column]; }

    @Override
    public Object getValueAt(int rowIndex, int columnIndex) {
        Item item = items.get(rowIndex);
        return switch (columnIndex) {
            case 0 -> item.url;
            case 1 -> item.language;
            case 2 -> item.status;
            case 3 -> item.activity;
            case 4 -> item.typeCount == null ? "" : item.typeCount;
            case 5 -> item.output == null ? "" : item.output;
            default -> "";
        };
    }
}
