package pablog.selextrace.launcher;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Locale;

public final class PrerequisiteChecker {
    public record CheckResult(boolean ok, String label, String detail) {
    }

    public CheckResult checkJavaSdk25() {
        int feature = Runtime.version().feature();
        boolean javacAvailable = commandWorks("javac", "-version");
        boolean ok = feature >= 25 && javacAvailable;
        String detail = ok
                ? "Java " + feature + " runtime and javac detected"
                : "Need Java SDK 25+; current runtime is " + feature + (javacAvailable ? "" : " and javac is unavailable");
        return new CheckResult(ok, "Java SDK", detail);
    }

    public CheckResult checkDocker() {
        boolean dockerNative = commandWorks("docker", "info");
        if (dockerNative) {
            return new CheckResult(true, "Docker", "Docker daemon reachable");
        }

        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win") && commandWorks("wsl", "docker", "info")) {
            return new CheckResult(true, "Docker", "Docker reachable through WSL fallback");
        }

        return new CheckResult(false, "Docker", "Docker daemon was not reachable");
    }

    public CheckResult checkLocalWebServerTool() {
        if (commandWorks("npx", "--version")) {
            return new CheckResult(true, "Web server", "npx detected");
        }
        if (commandWorks("python3", "--version")) {
            return new CheckResult(true, "Web server", "python3 detected");
        }
        if (commandWorks("python", "--version")) {
            return new CheckResult(true, "Web server", "python detected");
        }
        return new CheckResult(false, "Web server", "No npx, python3, or python executable found");
    }

    private boolean commandWorks(String... command) {
        ProcessBuilder builder = new ProcessBuilder(command);
        builder.redirectErrorStream(true);
        try {
            Process process = builder.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                while (reader.readLine() != null) {
                    // drain output
                }
            }
            return process.waitFor() == 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (IOException e) {
            return false;
        }
    }
}
