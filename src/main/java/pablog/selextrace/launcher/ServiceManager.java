package pablog.selextrace.launcher;

import java.awt.Desktop;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.Socket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public final class ServiceManager {
    public enum Status {
        STOPPED, STARTING, RUNNING, ERROR
    }

    public record ServiceSnapshot(Status postgres, Status backend, Status frontend, String details) {
    }

    public static final class ServiceHandle {
        private Process postgresProcess;
        private Process backendProcess;
        private Process frontendProcess;
        private final List<String> logs = new CopyOnWriteArrayList<>();
        private String dockerContainerName = "selextrace-postgres";
        private boolean postgresStartedByLauncher;
        private CommandPrefix dockerPrefix = CommandPrefix.docker();

        public List<String> logs() {
            return logs;
        }
    }

    public record CommandPrefix(List<String> prefix) {
        public static CommandPrefix docker() {
            return new CommandPrefix(List.of("docker"));
        }

        public static CommandPrefix wslDocker() {
            return new CommandPrefix(List.of("wsl", "docker"));
        }
    }

    public interface SnapshotListener extends Consumer<ServiceSnapshot> {
    }

    public ServiceHandle newHandle() {
        return new ServiceHandle();
    }

    public void startAll(ServiceHandle handle, LauncherConfig config, ArtifactManager.ArtifactBundle artifacts, SnapshotListener listener)
            throws IOException, InterruptedException {
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(artifacts, "artifacts");
        Objects.requireNonNull(listener, "listener");

        setSnapshot(listener, Status.STARTING, Status.STOPPED, Status.STOPPED, "Starting PostgreSQL...");
        startPostgres(handle, config, listener);

        setSnapshot(listener, Status.RUNNING, Status.STARTING, Status.STOPPED, "Starting backend...");
        startBackend(handle, config, artifacts, listener);

        setSnapshot(listener, Status.RUNNING, Status.RUNNING, Status.STARTING, "Starting frontend...");
        startFrontend(handle, config, artifacts, listener);

        setSnapshot(listener, Status.RUNNING, Status.RUNNING, Status.RUNNING, "All services started");
    }

    public void stopAll(ServiceHandle handle, SnapshotListener listener) {
        if (handle == null) {
            return;
        }
        safeDestroy(handle.frontendProcess);
        safeDestroy(handle.backendProcess);
        safeDestroy(handle.postgresProcess);
        stopDockerContainer(handle);
        setSnapshot(listener, Status.STOPPED, Status.STOPPED, Status.STOPPED, "All services stopped");
    }

    private void setSnapshot(SnapshotListener listener, Status postgres, Status backend, Status frontend, String details) {
        listener.accept(new ServiceSnapshot(postgres, backend, frontend, details));
    }

    public void openBrowser(int frontendPort) {
        if (!Desktop.isDesktopSupported()) {
            return;
        }
        try {
            Desktop.getDesktop().browse(URI.create("http://localhost:" + frontendPort));
        } catch (Exception ignored) {
        }
    }

    private void startPostgres(ServiceHandle handle, LauncherConfig config, SnapshotListener listener) throws IOException, InterruptedException {
        String image = "postgres:" + config.postgresqlMajorVersion();
        List<String> dockerCmd = resolveDockerPrefix(handle).prefix();

        if (!containerExists(dockerCmd, handle.dockerContainerName)) {
            List<String> command = new ArrayList<>(dockerCmd);
            command.addAll(List.of(
                    "run", "-d",
                    "--name", handle.dockerContainerName,
                    "--restart", "unless-stopped",
                    "-v", handle.dockerContainerName + "-data:/var/lib/postgresql",
                    "-e", "POSTGRES_DB=" + config.databaseName(),
                    "-e", "POSTGRES_USER=" + config.databaseUsername(),
                    "-e", "POSTGRES_PASSWORD=" + config.databasePassword(),
                    "-p", "5432:5432",
                    image
            ));
            runCommand(command, "postgres container");
            handle.postgresStartedByLauncher = true;
        } else {
            String state = containerState(dockerCmd, handle.dockerContainerName);
            if (state.contains("exited")) {
                runCommand(withCommandSuffix(dockerCmd, "start", handle.dockerContainerName), "postgres container");
                handle.postgresStartedByLauncher = true;
            }
        }

        waitForPort("127.0.0.1", 5432, Duration.ofMinutes(5), 250);
        waitForPostgresReady(dockerCmd, handle.dockerContainerName, config.databaseUsername(), config.databaseName(), Duration.ofMinutes(5), 250);
        handle.postgresProcess = null;
        setSnapshot(listener, Status.RUNNING, Status.STOPPED, Status.STOPPED, "PostgreSQL is ready");
    }

    private void startBackend(ServiceHandle handle, LauncherConfig config, ArtifactManager.ArtifactBundle artifacts, SnapshotListener listener)
            throws IOException, InterruptedException {
        String clientId = config.googleClientId();
        if (clientId == null || clientId.isBlank()) {
            clientId = "dummy-client-id";
        }
        String clientSecret = config.googleClientSecret();
        if (clientSecret == null || clientSecret.isBlank()) {
            clientSecret = "dummy-client-secret";
        }

        List<String> command = new ArrayList<>();
        command.add(resolveJavaExecutable());
        command.add("-jar");
        command.add(artifacts.backendJar().toAbsolutePath().toString());
        command.add("--server.port=" + config.backendPort());
        command.add("--spring.datasource.url=jdbc:postgresql://localhost:5432/" + encodeQueryValue(config.databaseName()));
        command.add("--spring.datasource.username=" + config.databaseUsername());
        command.add("--spring.datasource.password=" + config.databasePassword());
        command.add("--spring.jpa.hibernate.ddl-auto=update");
        command.add("--selextrace.security.google-client-id=" + clientId);
        command.add("--selextrace.security.google-client-secret=" + clientSecret);
        command.add("--selextrace.security.frontend-success-url=http://localhost:" + config.frontendPort());
        command.add("--selextrace.security.frontend-failure-url=http://localhost:" + config.frontendPort() + "/login");

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        pb.directory(artifacts.backendJar().toAbsolutePath().getParent().toFile());
        handle.backendProcess = pb.start();
        pumpProcess(handle.backendProcess, "backend", handle);
        waitForPort("127.0.0.1", config.backendPort(), Duration.ofMinutes(5), 250);
        setSnapshot(listener, Status.RUNNING, Status.RUNNING, Status.STOPPED, "Backend is ready");
    }

    private void startFrontend(ServiceHandle handle, LauncherConfig config, ArtifactManager.ArtifactBundle artifacts, SnapshotListener listener)
            throws IOException, InterruptedException {
        List<String> command = detectFrontendCommand(config.frontendPort(), artifacts.frontendDist());
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        pb.directory(artifacts.frontendDist().toAbsolutePath().toFile());
        handle.frontendProcess = pb.start();
        pumpProcess(handle.frontendProcess, "frontend", handle);
        waitForHttp("http://localhost:" + config.frontendPort(), Duration.ofMinutes(5), 250);
        setSnapshot(listener, Status.RUNNING, Status.RUNNING, Status.RUNNING, "Frontend is ready");
    }

    private List<String> detectFrontendCommand(int port, Path dist) {
        if (commandExists("npx")) {
            String npx = isWindows() ? "npx.cmd" : "npx";
            return List.of(npx, "--yes", "serve", "-s", dist.toAbsolutePath().toString(), "-l", String.valueOf(port), "--single");
        }
        if (commandExists("python3")) {
            return List.of("python3", "-m", "http.server", String.valueOf(port), "--directory", dist.toAbsolutePath().toString());
        }
        return List.of("python", "-m", "http.server", String.valueOf(port), "--directory", dist.toAbsolutePath().toString());
    }

    private CommandPrefix resolveDockerPrefix(ServiceHandle handle) {
        if (commandExists("docker")) {
            handle.dockerPrefix = CommandPrefix.docker();
            return handle.dockerPrefix;
        }
        if (isWindows() && commandExists("wsl")) {
            handle.dockerPrefix = CommandPrefix.wslDocker();
            return handle.dockerPrefix;
        }
        handle.dockerPrefix = CommandPrefix.docker();
        return handle.dockerPrefix;
    }

    private boolean containerExists(List<String> dockerCmd, String name) throws IOException, InterruptedException {
        String output = runCommandAndCapture(withCommandSuffix(dockerCmd, "ps", "-a", "--filter", "name=^/" + name + "$", "--format", "{{.ID}}"));
        return !output.isBlank();
    }

    private String containerState(List<String> dockerCmd, String name) throws IOException, InterruptedException {
        return runCommandAndCapture(withCommandSuffix(dockerCmd, "inspect", "-f", "{{.State.Status}}", name)).trim().toLowerCase(Locale.ROOT);
    }

    private void stopDockerContainer(ServiceHandle handle) {
        try {
            if (commandExists("docker")) {
                runCommand(List.of("docker", "stop", handle.dockerContainerName), "docker stop");
            } else if (isWindows() && commandExists("wsl")) {
                runCommand(List.of("wsl", "docker", "stop", handle.dockerContainerName), "docker stop");
            }
        } catch (Exception ignored) {
        }
    }

    private void pumpProcess(Process process, String tag, ServiceHandle handle) {
        Thread reader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    handle.logs().add("[" + tag + "] " + line);
                }
            } catch (IOException ignored) {
            }
        }, "selextrace-" + tag + "-logs");
        reader.setDaemon(true);
        reader.start();
    }

    private void waitForPort(String host, int port, Duration timeout, long pollMillis) throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, port), (int) pollMillis);
                return;
            } catch (IOException ignored) {
                Thread.sleep(pollMillis);
            }
        }
        throw new IOException("Timed out waiting for " + host + ":" + port);
    }

    private void waitForPostgresReady(List<String> dockerCmd, String containerName, String username, String database, Duration timeout, long pollMillis) throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        List<String> command = new ArrayList<>(dockerCmd);
        command.addAll(List.of("exec", containerName, "pg_isready", "-U", username, "-d", database));

        while (System.currentTimeMillis() < deadline) {
            try {
                ProcessBuilder pb = new ProcessBuilder(command);
                pb.redirectErrorStream(true);
                Process process = pb.start();
                int exitCode = process.waitFor();
                if (exitCode == 0) {
                    return;
                }
            } catch (IOException ignored) {
            }
            Thread.sleep(pollMillis);
        }
        throw new IOException("Timed out waiting for PostgreSQL container database system to start");
    }

    private void waitForHttp(String url, Duration timeout, long pollMillis) throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            try {
                java.net.http.HttpResponse<Void> response = java.net.http.HttpClient.newHttpClient()
                        .send(java.net.http.HttpRequest.newBuilder(URI.create(url)).GET().build(),
                                java.net.http.HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() / 100 == 2 || response.statusCode() == 3) {
                    return;
                }
            } catch (IOException ignored) {
            }
            Thread.sleep(pollMillis);
        }
        throw new IOException("Timed out waiting for " + url);
    }

    private void safeDestroy(Process process) {
        if (process != null && process.isAlive()) {
            // Terminate child processes recursively first (e.g. node.exe spawned by cmd.exe/npx.cmd on Windows)
            process.descendants().forEach(handle -> {
                if (handle.isAlive()) {
                    handle.destroyForcibly();
                }
            });
            process.destroy();
            try {
                if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }

    private void runCommand(List<String> command, String label) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            while (br.readLine() != null) {
                // drain
            }
        }
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IOException(label + " failed with exit code " + exit);
        }
    }

    private String runCommandAndCapture(List<String> command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        process.waitFor();
        return sb.toString();
    }

    private List<String> withCommandSuffix(List<String> prefix, String... suffix) {
        List<String> out = new ArrayList<>(prefix);
        for (String item : suffix) {
            out.add(item);
        }
        return out;
    }

    private boolean commandExists(String executable) {
        try {
            Process process = new ProcessBuilder(isWindows() ? List.of("where", executable) : List.of("sh", "-lc", "command -v " + executable))
                    .redirectErrorStream(true)
                    .start();
            return process.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private String resolveJavaExecutable() {
        String javaHome = System.getProperty("java.home");
        Path java = Path.of(javaHome, "bin", isWindows() ? "java.exe" : "java");
        if (java.toFile().exists()) {
            return java.toAbsolutePath().toString();
        }
        return "java";
    }

    private String encodeQueryValue(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
