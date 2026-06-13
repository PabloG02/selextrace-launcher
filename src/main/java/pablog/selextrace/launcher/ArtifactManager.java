package pablog.selextrace.launcher;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class ArtifactManager {
    public record ArtifactBundle(Path backendJar, Path frontendDist, String backendAssetId, String frontendAssetId) {
    }

    public record DownloadProgress(String message, int percent) {
    }

    private static final URI BACKEND_RELEASE_URI = URI.create(
            "https://api.github.com/repos/PabloG02/selextrace-backend/releases/tags/latest"
    );

    private static final URI FRONTEND_RELEASE_URI = URI.create(
            "https://api.github.com/repos/PabloG02/selextrace-frontend/releases/tags/latest"
    );

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final Path artifactsDir;
    private final Path backendJar;
    private final Path frontendZip;
    private final Path frontendDist;
    private final Path backendVersionFile;
    private final Path frontendVersionFile;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public ArtifactManager() {
        this(Path.of(".selextrace-artifacts"));
    }

    public ArtifactManager(Path artifactsDir) {
        this.artifactsDir = artifactsDir;
        this.backendJar = artifactsDir.resolve("backend.jar");
        this.frontendZip = artifactsDir.resolve("frontend.zip");
        this.frontendDist = artifactsDir.resolve("frontend-dist");
        this.backendVersionFile = artifactsDir.resolve("backend-version.txt");
        this.frontendVersionFile = artifactsDir.resolve("frontend-version.txt");
    }

    public ArtifactBundle downloadLatestArtifacts(BiConsumer<String, Integer> progressCallback) throws IOException, InterruptedException {
        Files.createDirectories(artifactsDir);

        JsonNode backendRelease = fetchRelease(BACKEND_RELEASE_URI);
        JsonNode frontendRelease = fetchRelease(FRONTEND_RELEASE_URI);

        List<Asset> backendParsedAssets = parseAssets(backendRelease, "Backend");
        List<Asset> frontendParsedAssets = parseAssets(frontendRelease, "Frontend");

        Asset backendAsset = backendParsedAssets.stream()
                .filter(a -> a.name().endsWith(".jar"))
                .max(Comparator.comparingLong(Asset::size))
                .orElseThrow(() -> new IOException("No backend jar asset found in latest release"));

        Asset frontendAsset = frontendParsedAssets.stream()
                .filter(a -> a.name().equalsIgnoreCase("frontend-build.zip") || a.name().endsWith(".zip"))
                .filter(a -> !a.name().endsWith(".jar"))
                .findFirst()
                .orElseThrow(() -> new IOException("No frontend zip asset found in latest release"));

        boolean backendNeedsDownload = needsDownload(backendVersionFile, backendAsset.id()) || Files.notExists(backendJar);
        boolean frontendNeedsDownload = needsDownload(frontendVersionFile, frontendAsset.id()) || Files.notExists(frontendZip);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<Path> backendFuture = CompletableFuture.completedFuture(backendJar);
            CompletableFuture<Path> frontendFuture = CompletableFuture.completedFuture(frontendZip);

            if (backendNeedsDownload) {
                backendFuture = CompletableFuture.supplyAsync(() -> {
                    try {
                        progressCallback.accept("Downloading backend JAR", 10);
                        downloadFile(backendAsset.url(), backendJar, progressCallback, 10, 55);
                        writeVersion(backendVersionFile, backendAsset.id());
                        return backendJar;
                    } catch (IOException | InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }, pool);
            } else {
                progressCallback.accept("Backend already cached", 55);
            }

            if (frontendNeedsDownload) {
                frontendFuture = CompletableFuture.supplyAsync(() -> {
                    try {
                        progressCallback.accept("Downloading frontend ZIP", 60);
                        downloadFile(frontendAsset.url(), frontendZip, progressCallback, 60, 85);
                        writeVersion(frontendVersionFile, frontendAsset.id());
                        return frontendZip;
                    } catch (IOException | InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }, pool);
            } else {
                progressCallback.accept("Frontend already cached", 85);
            }

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

            progressCallback.accept("Extracting frontend distribution", 90);
            extractZip(frontendZip, frontendDist);
            adjustBaseHref(frontendDist.resolve("index.html"));
            progressCallback.accept("Artifacts ready", 100);

            return new ArtifactBundle(backendJar, frontendDist, backendAsset.id(), frontendAsset.id());
        } finally {
            pool.shutdownNow();
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

    private void extractZip(Path zipFile, Path targetDir) throws IOException {
        deleteDirectoryIfExists(targetDir);
        Files.createDirectories(targetDir);
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                Path out = targetDir.resolve(entry.getName()).normalize();
                if (!out.startsWith(targetDir)) {
                    throw new IOException("Zip entry escaped target directory: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    try (OutputStream os = Files.newOutputStream(out, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                        zin.transferTo(os);
                    }
                }
            }
        }
    }

    private void deleteDirectoryIfExists(Path path) throws IOException {
        if (Files.notExists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
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

    private void adjustBaseHref(Path indexHtml) {
        if (Files.exists(indexHtml)) {
            try {
                String content = Files.readString(indexHtml, java.nio.charset.StandardCharsets.UTF_8);
                content = content.replace("<base href=\"/selextrace-frontend/\">", "<base href=\"/\">");
                Files.writeString(indexHtml, content, java.nio.charset.StandardCharsets.UTF_8);
            } catch (IOException ignored) {
            }
        }
    }

    private record Asset(String id, String name, String url, long size) {
    }
}
