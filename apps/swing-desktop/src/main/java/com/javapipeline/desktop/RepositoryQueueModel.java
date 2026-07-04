package com.javapipeline.desktop;

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

        Item(String url) { this.url = url; }
    }

    private static final String[] COLUMNS = {"Repository", "Status", "Activity", "Types", "Output"};
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
            case 1 -> item.status;
            case 2 -> item.activity;
            case 3 -> item.typeCount == null ? "" : item.typeCount;
            case 4 -> item.output == null ? "" : item.output;
            default -> "";
        };
    }
}
