package pablog.selextrace.launcher;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;

public final class ArtifactManager {
    public record ArtifactBundle(Path backendJar, String backendAssetId) {
    }

    public record DownloadProgress(String message, int percent) {
    }

    static final String FRONTEND_IMAGE = "ghcr.io/pablog02/selextrace-frontend:latest";

    private static final URI BACKEND_RELEASE_URI = URI.create(
            "https://api.github.com/repos/PabloG02/selextrace-backend/releases/tags/latest"
    );

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final Path artifactsDir;
    private final Path backendJar;
    private final Path backendVersionFile;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public ArtifactManager() {
        this(Path.of(".selextrace-artifacts"));
    }

    public ArtifactManager(Path artifactsDir) {
        this.artifactsDir = artifactsDir;
        this.backendJar = artifactsDir.resolve("backend.jar");
        this.backendVersionFile = artifactsDir.resolve("backend-version.txt");
    }

    public ArtifactBundle downloadLatestArtifacts(BiConsumer<String, Integer> progressCallback) throws IOException, InterruptedException {
        Files.createDirectories(artifactsDir);

        JsonNode backendRelease = fetchRelease(BACKEND_RELEASE_URI);

        List<Asset> backendParsedAssets = parseAssets(backendRelease, "Backend");

        Asset backendAsset = backendParsedAssets.stream()
                .filter(a -> a.name().endsWith(".jar"))
                .max(Comparator.comparingLong(Asset::size))
                .orElseThrow(() -> new IOException("No backend jar asset found in latest release"));

        boolean backendNeedsDownload = needsDownload(backendVersionFile, backendAsset.id()) || Files.notExists(backendJar);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<Path> backendFuture = CompletableFuture.completedFuture(backendJar);
            CompletableFuture<Void> frontendFuture = CompletableFuture.completedFuture(null);

            if (backendNeedsDownload) {
                backendFuture = CompletableFuture.supplyAsync(() -> {
                    try {
                        progressCallback.accept("Downloading backend JAR", 10);
                        downloadFile(backendAsset.url(), backendJar, progressCallback, 10, 75);
                        writeVersion(backendVersionFile, backendAsset.id());
                        return backendJar;
                    } catch (IOException | InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }, pool);
            } else {
                progressCallback.accept("Backend already cached", 75);
            }

            frontendFuture = CompletableFuture.runAsync(() -> {
                try {
                    progressCallback.accept("Pulling frontend Docker image", 80);
                    pullDockerImage(FRONTEND_IMAGE);
                } catch (IOException | InterruptedException e) {
                    throw new RuntimeException(e);
                }
            }, pool);

            try {
                backendFuture.join();
                frontendFuture.join();
            } catch (Exception e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                if (cause instanceof RuntimeException re && re.getCause() != null) {
                    cause = re.getCause();
                }
                if (cause instanceof IOException io) {
                    throw io;
                }
                if (cause instanceof InterruptedException ie) {
                    throw ie;
                }
                throw new IOException("Artifact download failed", cause);
            }

            progressCallback.accept("Artifacts ready", 100);

            return new ArtifactBundle(backendJar, backendAsset.id());
        } finally {
            pool.shutdownNow();
        }
    }

    private void pullDockerImage(String image) throws IOException, InterruptedException {
        List<String> command = resolveDockerCommand();
        command.add("pull");
        command.add(image);

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        Process process = pb.start();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            while (br.readLine() != null) {
                // drain
            }
        }
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IOException("docker pull failed for " + image + " (exit code " + exit + ")");
        }
    }

    private List<String> resolveDockerCommand() {
        if (commandExists("docker")) {
            return new ArrayList<>(List.of("docker"));
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win") && commandExists("wsl")) {
            return new ArrayList<>(List.of("wsl", "docker"));
        }
        return new ArrayList<>(List.of("docker"));
    }

    private boolean commandExists(String executable) {
        try {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            ProcessBuilder pb = os.contains("win")
                    ? new ProcessBuilder("where", executable)
                    : new ProcessBuilder("sh", "-lc", "command -v " + executable);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                while (br.readLine() != null) {
                    // drain
                }
            }
            return process.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private JsonNode fetchRelease(URI uri) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "SELEXTrace-Launcher")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("GitHub API returned HTTP " + response.statusCode() + " for " + uri);
        }
        return MAPPER.readTree(response.body());
    }

    private List<Asset> parseAssets(JsonNode release, String componentName) throws IOException {
        JsonNode assets = release.path("assets");
        if (!assets.isArray()) {
            throw new IOException(componentName + " release response did not include assets");
        }
        List<Asset> parsed = new ArrayList<>();
        for (JsonNode assetNode : assets) {
            parsed.add(new Asset(
                    assetNode.path("id").asString("0"),
                    assetNode.path("name").asString(""),
                    assetNode.path("browser_download_url").asString(""),
                    assetNode.path("size").asLong(0)
            ));
        }
        return parsed;
    }

    private void downloadFile(String url, Path target, BiConsumer<String, Integer> progressCallback, int startPercent, int endPercent)
            throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "SELEXTrace-Launcher")
                .GET()
                .build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() / 100 != 2) {
            throw new IOException("Download failed for " + url + " (HTTP " + response.statusCode() + ")");
        }

        long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
        Files.createDirectories(target.getParent());

        try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            byte[] buffer = new byte[8192];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
                total += read;
                if (contentLength > 0) {
                    int percent = startPercent + (int) ((total * (endPercent - startPercent)) / contentLength);
                    progressCallback.accept("Downloading " + target.getFileName(), Math.min(endPercent, percent));
                }
            }
        }
    }

    private boolean needsDownload(Path versionFile, String assetId) {
        if (Files.notExists(versionFile)) {
            return true;
        }
        try {
            String cached = Files.readString(versionFile).trim();
            return !cached.equals(assetId);
        } catch (IOException e) {
            return true;
        }
    }

    private void writeVersion(Path file, String assetId) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, assetId, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private record Asset(String id, String name, String url, long size) {
    }
}
