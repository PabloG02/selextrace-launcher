package pablog.selextrace.launcher;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class LauncherConfigStore {
    private final Path configPath;

    public LauncherConfigStore() {
        this(Path.of("selextrace.cfg"));
    }

    public LauncherConfigStore(Path configPath) {
        this.configPath = configPath;
    }

    public LauncherConfig load() {
        LauncherConfig defaults = LauncherConfig.defaults();
        if (!Files.exists(configPath)) {
            return defaults;
        }

        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(configPath)) {
            props.load(in);
        } catch (IOException e) {
            return defaults;
        }

        return new LauncherConfig(
                Boolean.parseBoolean(props.getProperty("theme.dark", Boolean.toString(defaults.darkTheme()))),
                props.getProperty("db.name", defaults.databaseName()),
                props.getProperty("db.username", defaults.databaseUsername()),
                props.getProperty("db.password", defaults.databasePassword()),
                parseInt(props.getProperty("db.pgMajor"), defaults.postgresqlMajorVersion(), 18),
                parseInt(props.getProperty("port.backend"), defaults.backendPort(), 1, 65535),
                parseInt(props.getProperty("port.frontend"), defaults.frontendPort(), 1, 65535),
                props.getProperty("oauth.clientId", defaults.googleClientId()),
                props.getProperty("oauth.clientSecret", defaults.googleClientSecret())
        );
    }

    public void save(LauncherConfig config) throws IOException {
        Properties props = new Properties();
        props.setProperty("theme.dark", Boolean.toString(config.darkTheme()));
        props.setProperty("db.name", config.databaseName());
        props.setProperty("db.username", config.databaseUsername());
        props.setProperty("db.password", config.databasePassword());
        props.setProperty("db.pgMajor", Integer.toString(config.postgresqlMajorVersion()));
        props.setProperty("port.backend", Integer.toString(config.backendPort()));
        props.setProperty("port.frontend", Integer.toString(config.frontendPort()));
        props.setProperty("oauth.clientId", config.googleClientId());
        props.setProperty("oauth.clientSecret", config.googleClientSecret());

        try (OutputStream out = Files.newOutputStream(configPath)) {
            props.store(out, "SELEXTrace Launcher configuration");
        }
    }

    private static int parseInt(String value, int fallback, int min) {
        return parseInt(value, fallback, min, Integer.MAX_VALUE);
    }

    private static int parseInt(String value, int fallback, int min, int max) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed < min || parsed > max) {
                return fallback;
            }
            return parsed;
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
