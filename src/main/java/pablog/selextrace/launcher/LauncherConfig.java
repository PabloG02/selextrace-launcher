package pablog.selextrace.launcher;

public record LauncherConfig(
        boolean darkTheme,
        String databaseName,
        String databaseUsername,
        String databasePassword,
        int postgresqlMajorVersion,
        int backendPort,
        int frontendPort,
        String googleClientId,
        String googleClientSecret,
        String bindAddress
) {
    public static LauncherConfig defaults() {
        return new LauncherConfig(
                true,
                "selextrace",
                "selextrace",
                "",
                18,
                8080,
                4200,
                "",
                "",
                "localhost"
        );
    }
}
