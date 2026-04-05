package com.ttl.tabletennis.config;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.util.Objects;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class StartupBrowserLauncher {

    private static final Logger log = LoggerFactory.getLogger(StartupBrowserLauncher.class);

    private final Environment environment;

    @Value("${app.openBrowserOnStartup:true}")
    private boolean openBrowserOnStartup;

    @Value("${app.openBrowserPath:/}")
    private String openBrowserPath;

    @Value("${app.openBrowserDelayMs:1200}")
    private long openBrowserDelayMs;

    @Value("${app.webUiUrl:http://localhost:5173}")
    private String webUiUrl;

    @Value("${app.autoStartWebUiDevServer:true}")
    private boolean autoStartWebUiDevServer;

    @Value("${app.webUiDir:./web}")
    private String webUiDir;

    @Value("${app.webUiStartTimeoutMs:20000}")
    private long webUiStartTimeoutMs;

    @Value("${app.webUiNpmCommand:}")
    private String webUiNpmCommand;

    @Value("${app.webUiAutoInstallDeps:true}")
    private boolean webUiAutoInstallDeps;

    @Value("${app.webUiFallbackToBackendOnFailure:false}")
    private boolean webUiFallbackToBackendOnFailure;

    private final AtomicReference<Process> webUiProcess = new AtomicReference<>();

    public StartupBrowserLauncher(Environment environment) {
        this.environment = environment;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!openBrowserOnStartup) {
            return;
        }
        if (GraphicsEnvironment.isHeadless()) {
            log.info("Browser auto-open skipped: JVM is headless");
            return;
        }
        if (!Desktop.isDesktopSupported()) {
            log.info("Browser auto-open skipped: Desktop API not supported");
            return;
        }

        int port = resolveServerPort();
        String backendPath = openBrowserPath.startsWith("/") ? openBrowserPath : "/" + openBrowserPath;
        String backendUrl = "http://localhost:" + port + backendPath;
        long delay = Math.max(0L, openBrowserDelayMs);

        Thread opener = new Thread(() -> {
            String url = resolvePreferredUrl(backendUrl);
            openInBrowser(url, delay);
        }, "startup-browser-opener");
        opener.setDaemon(true);
        opener.start();
    }

    private int resolveServerPort() {
        String local = environment.getProperty("local.server.port");
        if (local != null && !local.isBlank()) {
            return Integer.parseInt(local);
        }
        String configured = environment.getProperty("server.port", "8080");
        return Integer.parseInt(configured);
    }

    private void openInBrowser(String url, long delayMs) {
        try {
            if (delayMs > 0) {
                Thread.sleep(delayMs);
            }
            Desktop.getDesktop().browse(URI.create(url));
            log.info("Opened browser: {}", url);
        } catch (Exception e) {
            log.warn("Could not open browser for {}", url, e);
        }
    }

    private String resolvePreferredUrl(String backendUrl) {
        String uiUrl = webUiUrl == null ? "" : webUiUrl.trim();
        if (uiUrl.isBlank()) {
            return backendUrl;
        }

        if (isUrlReachable(uiUrl, 600)) {
            log.info("Using existing web UI at {}", uiUrl);
            return uiUrl;
        }

        if (autoStartWebUiDevServer && tryStartWebUiDevServer()) {
            if (waitForUrl(uiUrl, webUiStartTimeoutMs)) {
                log.info("Web UI became ready at {}", uiUrl);
                return uiUrl;
            }
            log.warn("Web UI dev server did not become ready in time: {}", uiUrl);
        } else if (!autoStartWebUiDevServer) {
            log.info("Web UI auto-start disabled; opening configured web UI URL {}", uiUrl);
        } else {
            log.warn("Web UI dev server could not start: {}", uiUrl);
        }

        if (webUiFallbackToBackendOnFailure) {
            log.warn("Falling back to backend URL {} because app.webUiFallbackToBackendOnFailure=true", backendUrl);
            return backendUrl;
        }
        return uiUrl;
    }

    private boolean tryStartWebUiDevServer() {
        if (webUiProcess.get() != null) {
            return true;
        }
        String host = parseHost(webUiUrl, "127.0.0.1");
        int port = parsePort(webUiUrl, 5173);
        String npmCommand = resolveNpmCommand();

        if (npmCommand == null) {
            log.warn("Could not find npm command for auto-starting web UI");
            return false;
        }

        String webUiDirEscaped = shellQuote(webUiDir);
        StringBuilder command = new StringBuilder()
                .append("cd ")
                .append(webUiDirEscaped)
                .append(" && ");
        if (webUiAutoInstallDeps && !Path.of(webUiDir, "node_modules").toFile().exists()) {
            command.append(npmCommand).append(" install --legacy-peer-deps --no-audit --no-fund").append(" && ");
        }
        command.append(npmCommand)
                .append(" run dev -- --host ")
                .append(host)
                .append(" --port ")
                .append(port)
                .append(" --strictPort");

        try {
            Process process = buildShellProcess(command.toString())
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                    .start();

            webUiProcess.set(process);
            log.info("Started web UI dev server from {} using {}", webUiDir, npmCommand);
            return true;
        } catch (IOException e) {
            log.warn("Could not start web UI dev server in {}: {}", webUiDir, e.getMessage());
            return false;
        }
    }

    private boolean waitForUrl(String url, long timeoutMs) {
        Instant deadline = Instant.now().plusMillis(Math.max(500L, timeoutMs));
        while (Instant.now().isBefore(deadline)) {
            if (isUrlReachable(url, 700)) {
                return true;
            }
            try {
                Thread.sleep(500L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private boolean isUrlReachable(String url, int timeoutMs) {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            int status = conn.getResponseCode();
            conn.disconnect();
            return status >= 200 && status < 500;
        } catch (Exception ignored) {
            return false;
        }
    }

    private String resolveNpmCommand() {
        String configured = webUiNpmCommand == null ? "" : webUiNpmCommand.trim();
        if (!configured.isBlank()) {
            return configured;
        }

        String[] candidates = new String[] {
                "npm",
                "/opt/homebrew/bin/npm",
                "/usr/local/bin/npm",
                "/usr/bin/npm"
        };

        for (String candidate : candidates) {
            if (canExecuteNpm(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private boolean canExecuteNpm(String candidate) {
        try {
            Process p = buildShellProcess(candidate + " --version")
                    .redirectErrorStream(true)
                    .start();
            boolean done = p.waitFor(2, TimeUnit.SECONDS);
            if (!done) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static ProcessBuilder buildShellProcess(String command) {
        String osName = System.getProperty("os.name", "").toLowerCase();
        if (osName.contains("win")) {
            return new ProcessBuilder("cmd.exe", "/c", command);
        }
        return new ProcessBuilder("/bin/zsh", "-lc", command);
    }

    private static String parseHost(String rawUrl, String fallback) {
        try {
            URL url = new URL(Objects.requireNonNullElse(rawUrl, ""));
            return url.getHost() == null || url.getHost().isBlank() ? fallback : url.getHost();
        } catch (MalformedURLException e) {
            return fallback;
        }
    }

    private static int parsePort(String rawUrl, int fallback) {
        try {
            URL url = new URL(Objects.requireNonNullElse(rawUrl, ""));
            return url.getPort() > 0 ? url.getPort() : (url.getDefaultPort() > 0 ? url.getDefaultPort() : fallback);
        } catch (MalformedURLException e) {
            return fallback;
        }
    }

    private static String shellQuote(String value) {
        if (value == null || value.isBlank()) {
            return "''";
        }
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }

    @PreDestroy
    public void shutdownWebUiProcess() {
        Process process = webUiProcess.getAndSet(null);
        if (process == null) {
            return;
        }
        if (!process.isAlive()) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(Duration.ofSeconds(3).toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }
}
