package pablog.selextrace.launcher;

import javax.swing.SwingUtilities;

public final class Main {
    private Main() {
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            LauncherConfigStore configStore = new LauncherConfigStore();
            LauncherConfig config = configStore.load();
            ThemeManager.apply(config.darkTheme());
            LauncherFrame frame = new LauncherFrame(configStore, config);
            frame.setVisible(true);
        });
    }
}
