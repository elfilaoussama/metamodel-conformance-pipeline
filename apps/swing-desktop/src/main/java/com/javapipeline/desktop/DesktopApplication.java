package com.javapipeline.desktop;

import javax.swing.*;

public final class DesktopApplication {
    private DesktopApplication() { }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
            catch (Exception ignored) { }
            new AnalysisFrame().setVisible(true);
        });
    }
}
