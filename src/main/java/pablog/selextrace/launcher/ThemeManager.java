package pablog.selextrace.launcher;

import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.themes.FlatMacDarkLaf;
import com.formdev.flatlaf.themes.FlatMacLightLaf;

public final class ThemeManager {
    private ThemeManager() {
    }

    public static void apply(boolean dark) {
        try {
            if (dark) {
                FlatMacDarkLaf.setup();
            } else {
                FlatMacLightLaf.setup();
            }
        } catch (Exception e) {
            FlatLaf.setup(dark ? new FlatMacDarkLaf() : new FlatMacLightLaf());
        }
        FlatLaf.updateUI();
    }
}
