package org.gradle.wrapper;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Minimal, auditable Gradle bootstrap used only to download, verify, unpack,
 * and execute the Gradle distribution declared in gradle-wrapper.properties.
 */
public final class GradleWrapperMain {
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int MAX_REDIRECTS = 10;

    private GradleWrapperMain() {}

    public static void main(String[] args) {
        try {
            Path jarPath = Path.of(GradleWrapperMain.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            Path wrapperDir = Files.isDirectory(jarPath) ? jarPath : jarPath.getParent();
            Path propertiesPath = wrapperDir.resolve("gradle-wrapper.properties");

            Properties properties = new Properties();
            try (InputStream input = Files.newInputStream(propertiesPath)) {
                properties.load(input);
            }

            String distributionUrl = required(properties, "distributionUrl").replace("\\:", ":");
            String expectedSha256 = properties.getProperty("distributionSha256Sum", "").trim();
            String archiveName = Path.of(URI.create(distributionUrl).getPath()).getFileName().toString();
            String distributionName = archiveName.replaceFirst("\\.zip$", "");
            String folderName = distributionName.replaceFirst("-(bin|all)$", "");

            Path userHome = Path.of(System.getProperty("user.home"));
            Path cacheRoot = userHome.resolve(".gradle/wrapper/dists/orbit-launcher").resolve(distributionName);
            Path archive = cacheRoot.resolve(archiveName);
            Path installDir = cacheRoot.resolve(folderName);
            Path executable = installDir.resolve("bin").resolve(isWindows() ? "gradle.bat" : "gradle");

            if (!Files.isRegularFile(executable)) {
                Files.createDirectories(cacheRoot);
                if (!Files.isRegularFile(archive) || !checksumMatches(archive, expectedSha256)) {
                    Files.deleteIfExists(archive);
                    download(distributionUrl, archive);
                }
                verifyChecksum(archive, expectedSha256);
                deleteRecursively(installDir);
                unzip(archive, cacheRoot);
                if (!Files.isRegularFile(executable)) {
                    throw new IOException("Gradle executable was not found after extracting " + archive);
                }
                if (!isWindows()) executable.toFile().setExecutable(true);
            }

            List<String> command = new ArrayList<>();
            if (isWindows()) {
                command.add(executable.toString());
            } else {
                // Invoke through PATH's sh. This avoids desktop Linux shebang
                // paths such as /bin/sh failing inside native Termux.
                command.add("sh");
                command.add(executable.toString());
            }
            for (String arg : args) command.add(arg);

            Process process = new ProcessBuilder(command)
                    .directory(Path.of(System.getProperty("user.dir")).toFile())
                    .inheritIO()
                    .start();
            System.exit(process.waitFor());
        } catch (Exception error) {
            System.err.println("Gradle bootstrap failed: " + error.getMessage());
            error.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + key);
        return value.trim();
    }

    private static void download(String source, Path destination) throws IOException {
        Path temporary = destination.resolveSibling(destination.getFileName() + ".part");
        Files.deleteIfExists(temporary);
        URL url = URI.create(source).toURL();

        for (int redirect = 0; redirect <= MAX_REDIRECTS; redirect++) {
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(30_000);
            connection.setReadTimeout(120_000);
            connection.setRequestProperty("User-Agent", "OrbitLauncher-GradleBootstrap/1");
            int status = connection.getResponseCode();
            if (status >= 300 && status < 400) {
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null) throw new IOException("Redirect without Location from " + url);
                url = URI.create(url.toString()).resolve(location).toURL();
                continue;
            }
            if (status < 200 || status >= 300) {
                connection.disconnect();
                throw new IOException("HTTP " + status + " while downloading " + url);
            }

            System.out.println("Downloading " + url);
            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                 BufferedOutputStream output = new BufferedOutputStream(Files.newOutputStream(temporary))) {
                byte[] buffer = new byte[BUFFER_SIZE];
                long total = 0;
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    output.write(buffer, 0, read);
                    total += read;
                    if (total % (16L * 1024 * 1024) < BUFFER_SIZE) {
                        System.out.printf("Downloaded %.1f MB%n", total / 1048576.0);
                    }
                }
            } finally {
                connection.disconnect();
            }
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        throw new IOException("Too many redirects while downloading " + source);
    }

    private static void verifyChecksum(Path archive, String expected) throws Exception {
        if (expected.isBlank()) return;
        String actual = sha256(archive);
        if (!actual.equalsIgnoreCase(expected)) {
            Files.deleteIfExists(archive);
            throw new SecurityException("SHA-256 mismatch. Expected " + expected + " but got " + actual);
        }
    }

    private static boolean checksumMatches(Path archive, String expected) {
        if (expected.isBlank()) return true;
        try { return sha256(archive).equalsIgnoreCase(expected); }
        catch (Exception ignored) { return false; }
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new BufferedInputStream(Files.newInputStream(file))) {
            byte[] buffer = new byte[BUFFER_SIZE];
            int read;
            while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void unzip(Path archive, Path destination) throws IOException {
        Path normalizedDestination = destination.toAbsolutePath().normalize();
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(Files.newInputStream(archive)))) {
            ZipEntry entry;
            byte[] buffer = new byte[BUFFER_SIZE];
            while ((entry = zip.getNextEntry()) != null) {
                Path output = normalizedDestination.resolve(entry.getName()).normalize();
                if (!output.startsWith(normalizedDestination)) {
                    throw new IOException("Blocked unsafe ZIP entry: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(output);
                } else {
                    Files.createDirectories(output.getParent());
                    try (BufferedOutputStream stream = new BufferedOutputStream(Files.newOutputStream(output))) {
                        int read;
                        while ((read = zip.read(buffer)) >= 0) stream.write(buffer, 0, read);
                    }
                }
                zip.closeEntry();
            }
        }
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) return;
        try (var stream = Files.walk(path)) {
            stream.sorted((a, b) -> b.compareTo(a)).forEach(item -> {
                try { Files.deleteIfExists(item); }
                catch (IOException error) { throw new RuntimeException(error); }
            });
        } catch (RuntimeException error) {
            if (error.getCause() instanceof IOException io) throw io;
            throw error;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }
}
