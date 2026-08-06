package one.animex;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.provider.MediaStore;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class EpisodeDownloadService extends Service {
    static final String EXTRA_URL = "url";
    static final String EXTRA_TITLE = "title";
    static final String EXTRA_REFERER = "referer";
    static final String EXTRA_USER_AGENT = "userAgent";
    static final String EXTRA_COOKIE = "cookie";

    private static final String CHANNEL_ID = "animex_episode_downloads";
    private static final int NOTIFICATION_ID = 5287;
    private static final int BUFFER_SIZE = 64 * 1024;
    private static final int MAX_MANIFEST_BYTES = 4 * 1024 * 1024;
    private static final String TEMP_DIRECTORY = "episode-downloads";

    private static final Pattern BANDWIDTH =
            Pattern.compile("(?:^|,)BANDWIDTH=(\\d+)");
    private static final Pattern MAP_URI =
            Pattern.compile("URI=\"([^\"]+)\"");

    private NotificationManager notifications;

    @Override
    public void onCreate() {
        super.onCreate();
        notifications = (NotificationManager) getSystemService(
                Context.NOTIFICATION_SERVICE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Episode downloads",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Animex episode download progress");
            notifications.createNotificationChannel(channel);
        }

        cleanupPrivateTemporaryFiles();
        cleanupPendingMediaStoreFiles();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_NOT_STICKY;
        }

        String url = intent.getStringExtra(EXTRA_URL);
        String title = intent.getStringExtra(EXTRA_TITLE);
        String referer = intent.getStringExtra(EXTRA_REFERER);
        String userAgent = intent.getStringExtra(EXTRA_USER_AGENT);
        String cookie = intent.getStringExtra(EXTRA_COOKIE);

        if (url == null || url.trim().isEmpty()) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        startForeground(
                NOTIFICATION_ID,
                notification("Preparing episode download", 0, true, false));

        Thread worker = new Thread(
                () -> download(
                        startId,
                        url,
                        title == null ? "Animex episode" : title,
                        referer,
                        userAgent,
                        cookie),
                "AnimexEpisodeDownload");
        worker.start();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void download(
            int startId,
            String url,
            String title,
            String referer,
            String userAgent,
            String cookie) {
        File temporaryFile = null;

        try {
            Playlist playlist = resolvePlaylist(
                    new URL(url), referer, userAgent, cookie, 0);
            validatePlaylist(playlist.lines);

            boolean fragmentedMp4 = isFragmentedMp4(playlist.lines);
            String extension = fragmentedMp4 ? ".mp4" : ".ts";
            String mime = fragmentedMp4 ? "video/mp4" : "video/mp2t";
            String fileName = cleanFileName(title)
                    + "-"
                    + timestamp()
                    + extension;

            File temporaryDirectory = temporaryDirectory();
            temporaryFile = File.createTempFile(
                    "animex-episode-",
                    extension + ".part",
                    temporaryDirectory);

            try (FileOutputStream rawOutput = new FileOutputStream(temporaryFile);
                 BufferedOutputStream output = new BufferedOutputStream(
                         rawOutput,
                         BUFFER_SIZE)) {
                downloadPlaylist(
                        playlist,
                        referer,
                        userAgent,
                        cookie,
                        output);
                output.flush();
                rawOutput.getFD().sync();
            }

            if (!temporaryFile.isFile() || temporaryFile.length() <= 0L) {
                throw new IOException("The downloaded episode was empty");
            }

            notifications.notify(
                    NOTIFICATION_ID,
                    notification("Finalizing episode", 99, false, false));

            String savedLocation = publishCompletedFile(
                    temporaryFile,
                    fileName,
                    mime);

            notifications.notify(
                    NOTIFICATION_ID,
                    notification(savedLocation, 100, false, true));
        } catch (Exception error) {
            notifications.notify(
                    NOTIFICATION_ID,
                    notification(
                            "Download failed: " + safeMessage(error),
                            0,
                            false,
                            false));
        } finally {
            if (temporaryFile != null && temporaryFile.exists()) {
                // This is app-private. A failed download cannot leave hidden public data.
                temporaryFile.delete();
            }
            stopForeground(false);
            stopSelf(startId);
        }
    }

    private String publishCompletedFile(
            File source,
            String fileName,
            String mime) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return publishToMediaStore(source, fileName, mime);
        }
        return publishLegacy(source, fileName);
    }

    private String publishToMediaStore(
            File source,
            String fileName,
            String mime) throws IOException {
        Uri mediaUri = null;
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, fileName);
            values.put(MediaStore.Video.Media.MIME_TYPE, mime);
            values.put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/Animex");
            values.put(MediaStore.Video.Media.IS_PENDING, 1);

            mediaUri = getContentResolver().insert(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    values);
            if (mediaUri == null) {
                throw new IOException("Unable to create the final video file");
            }

            OutputStream mediaOutput = getContentResolver()
                    .openOutputStream(mediaUri, "w");
            if (mediaOutput == null) {
                throw new IOException("Unable to open the final video file");
            }

            try (OutputStream output = new BufferedOutputStream(
                    mediaOutput,
                    BUFFER_SIZE)) {
                copyFile(source, output);
                output.flush();
            }

            ContentValues completed = new ContentValues();
            completed.put(MediaStore.Video.Media.IS_PENDING, 0);
            completed.put(MediaStore.Video.Media.SIZE, source.length());
            int updated = getContentResolver().update(
                    mediaUri,
                    completed,
                    null,
                    null);
            if (updated <= 0) {
                throw new IOException("Unable to finalize the video file");
            }

            return "Saved to Movies/Animex";
        } catch (Exception error) {
            if (mediaUri != null) {
                try {
                    getContentResolver().delete(mediaUri, null, null);
                } catch (Exception ignored) {
                    // The next service start also removes orphaned pending rows.
                }
            }
            if (error instanceof IOException) {
                throw (IOException) error;
            }
            throw new IOException(safeMessage(error), error);
        }
    }

    private String publishLegacy(File source, String fileName) throws IOException {
        File movies = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_MOVIES);
        File directory = new File(movies, "Animex");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Unable to create Movies/Animex");
        }

        File destination = uniqueFile(directory, fileName);
        File staging = new File(directory, "." + destination.getName() + ".part");
        try {
            try (OutputStream output = new BufferedOutputStream(
                    new FileOutputStream(staging),
                    BUFFER_SIZE)) {
                copyFile(source, output);
                output.flush();
            }

            if (!staging.renameTo(destination)) {
                try (OutputStream output = new BufferedOutputStream(
                        new FileOutputStream(destination),
                        BUFFER_SIZE)) {
                    copyFile(staging, output);
                    output.flush();
                }
                staging.delete();
            }
            return "Saved to " + destination.getAbsolutePath();
        } catch (Exception error) {
            staging.delete();
            destination.delete();
            if (error instanceof IOException) {
                throw (IOException) error;
            }
            throw new IOException(safeMessage(error), error);
        }
    }

    private File uniqueFile(File directory, String fileName) {
        File first = new File(directory, fileName);
        if (!first.exists()) {
            return first;
        }

        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        String extension = dot > 0 ? fileName.substring(dot) : "";
        for (int index = 2; index < 10_000; index++) {
            File candidate = new File(
                    directory,
                    base + " (" + index + ")" + extension);
            if (!candidate.exists()) {
                return candidate;
            }
        }
        return new File(directory, base + "-" + System.currentTimeMillis() + extension);
    }

    private void cleanupPrivateTemporaryFiles() {
        File directory = new File(getCacheDir(), TEMP_DIRECTORY);
        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isFile()) {
                file.delete();
            }
        }
    }

    private void cleanupPendingMediaStoreFiles() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return;
        }

        Uri collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        String[] projection = {MediaStore.Video.Media._ID};
        String selection = MediaStore.Video.Media.IS_PENDING
                + "=1 AND "
                + MediaStore.Video.Media.RELATIVE_PATH
                + " LIKE ?";
        String[] arguments = {"%Animex%"};

        try (Cursor cursor = getContentResolver().query(
                collection,
                projection,
                selection,
                arguments,
                null)) {
            if (cursor == null) {
                return;
            }
            int idColumn = cursor.getColumnIndexOrThrow(
                    MediaStore.Video.Media._ID);
            while (cursor.moveToNext()) {
                Uri item = ContentUris.withAppendedId(
                        collection,
                        cursor.getLong(idColumn));
                try {
                    getContentResolver().delete(item, null, null);
                } catch (Exception ignored) {
                    // Best-effort cleanup of rows created by earlier app versions.
                }
            }
        } catch (Exception ignored) {
            // Some OEM MediaStore implementations restrict pending-row queries.
        }
    }

    private File temporaryDirectory() throws IOException {
        File directory = new File(getCacheDir(), TEMP_DIRECTORY);
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Unable to create private download storage");
        }
        return directory;
    }

    private Playlist resolvePlaylist(
            URL url,
            String referer,
            String userAgent,
            String cookie,
            int depth) throws IOException {
        if (depth > 4) {
            throw new IOException("Too many nested HLS playlists");
        }

        String manifest = fetchText(
                url.toString(),
                referer,
                userAgent,
                cookie);
        String[] lines = manifest.replace("\r", "").split("\n");

        URL variant = highestVariant(url, lines);
        if (variant != null) {
            return resolvePlaylist(
                    variant,
                    referer,
                    userAgent,
                    cookie,
                    depth + 1);
        }
        return new Playlist(url, lines);
    }

    private void validatePlaylist(String[] lines) throws IOException {
        boolean hasSegments = false;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith("#EXT-X-KEY:")) {
                String upper = line.toUpperCase(Locale.US);
                if (!upper.contains("METHOD=NONE")) {
                    throw new IOException("Encrypted or DRM HLS is not supported");
                }
            } else if (line.startsWith("#EXT-X-BYTERANGE")) {
                throw new IOException("Byte-range HLS is not supported");
            } else if (!line.isEmpty() && !line.startsWith("#")) {
                hasSegments = true;
            }
        }
        if (!hasSegments) {
            throw new IOException("HLS playlist contains no media segments");
        }
    }

    private boolean isFragmentedMp4(String[] lines) {
        for (String raw : lines) {
            String line = raw.trim().toLowerCase(Locale.US);
            if (line.startsWith("#ext-x-map:")
                    || line.contains(".m4s")
                    || line.contains(".mp4")) {
                return true;
            }
        }
        return false;
    }

    private void downloadPlaylist(
            Playlist playlist,
            String referer,
            String userAgent,
            String cookie,
            OutputStream output) throws IOException {
        URL initialization = null;
        List<URL> segments = new ArrayList<>();

        for (String raw : playlist.lines) {
            String line = raw.trim();
            if (line.startsWith("#EXT-X-MAP:")) {
                Matcher matcher = MAP_URI.matcher(line);
                if (matcher.find()) {
                    initialization = new URL(playlist.url, matcher.group(1));
                }
            } else if (!line.isEmpty() && !line.startsWith("#")) {
                segments.add(new URL(playlist.url, line));
            }
        }

        if (segments.isEmpty()) {
            throw new IOException("HLS playlist contains no media segments");
        }

        if (initialization != null) {
            downloadPart(initialization, referer, userAgent, cookie, output);
        }

        for (int index = 0; index < segments.size(); index++) {
            try {
                downloadPart(
                        segments.get(index),
                        referer,
                        userAgent,
                        cookie,
                        output);
            } catch (IOException error) {
                throw new IOException(
                        "Segment "
                                + (index + 1)
                                + "/"
                                + segments.size()
                                + " failed: "
                                + safeMessage(error),
                        error);
            }

            int progress = Math.max(
                    1,
                    Math.min(98, ((index + 1) * 98) / segments.size()));
            notifications.notify(
                    NOTIFICATION_ID,
                    notification("Downloading episode", progress, false, false));
        }
    }

    private URL highestVariant(URL base, String[] lines) throws IOException {
        URL best = null;
        long bestBandwidth = -1L;
        long pendingBandwidth = -1L;

        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                Matcher matcher = BANDWIDTH.matcher(
                        line.substring("#EXT-X-STREAM-INF:".length()));
                pendingBandwidth = matcher.find()
                        ? Long.parseLong(matcher.group(1))
                        : 0L;
            } else if (pendingBandwidth >= 0L
                    && !line.isEmpty()
                    && !line.startsWith("#")) {
                if (pendingBandwidth > bestBandwidth) {
                    bestBandwidth = pendingBandwidth;
                    best = new URL(base, line);
                }
                pendingBandwidth = -1L;
            }
        }
        return best;
    }

    private void downloadPart(
            URL url,
            String referer,
            String userAgent,
            String cookie,
            OutputStream output) throws IOException {
        HttpURLConnection connection = connect(
                url.toString(),
                referer,
                userAgent,
                cookie);
        try {
            try (InputStream input = new BufferedInputStream(
                    checkedInput(connection),
                    BUFFER_SIZE)) {
                copy(input, output);
            }
        } finally {
            connection.disconnect();
        }
    }

    private String fetchText(
            String url,
            String referer,
            String userAgent,
            String cookie) throws IOException {
        HttpURLConnection connection = connect(
                url,
                referer,
                userAgent,
                cookie);
        try {
            try (InputStream input = checkedInput(connection)) {
                return readText(input, MAX_MANIFEST_BYTES);
            }
        } finally {
            connection.disconnect();
        }
    }

    private HttpURLConnection connect(
            String url,
            String referer,
            String userAgent,
            String cookie) throws IOException {
        HttpURLConnection connection = (HttpURLConnection)
                new URL(url).openConnection();
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(60_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "*/*");

        if (userAgent != null && !userAgent.trim().isEmpty()) {
            connection.setRequestProperty("User-Agent", userAgent);
        }
        if (referer != null && !referer.trim().isEmpty()) {
            connection.setRequestProperty("Referer", referer);
        }
        if (cookie != null && !cookie.trim().isEmpty()) {
            connection.setRequestProperty("Cookie", cookie);
        }

        connection.connect();
        return connection;
    }

    private InputStream checkedInput(HttpURLConnection connection)
            throws IOException {
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) {
            throw new IOException("HTTP " + code);
        }
        return connection.getInputStream();
    }

    private void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = input.read(buffer)) != -1) {
            output.write(buffer, 0, read);
        }
    }

    private void copyFile(File source, OutputStream output) throws IOException {
        try (InputStream input = new BufferedInputStream(
                new FileInputStream(source),
                BUFFER_SIZE)) {
            copy(input, output);
        }
    }

    private String readText(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > limit) {
                throw new IOException("HLS manifest is too large");
            }
            bytes.write(buffer, 0, read);
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    private Notification notification(
            String text,
            int progress,
            boolean indeterminate,
            boolean complete) {
        Notification.Builder builder = Build.VERSION.SDK_INT
                >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        Intent open = new Intent(this, MainActivity.class);
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pending = PendingIntent.getActivity(
                this,
                0,
                open,
                pendingFlags);

        builder.setSmallIcon(
                        complete
                                ? android.R.drawable.stat_sys_download_done
                                : android.R.drawable.stat_sys_download)
                .setContentTitle(
                        complete
                                ? "Animex episode downloaded"
                                : "Animex episode download")
                .setContentText(text)
                .setOnlyAlertOnce(true)
                .setContentIntent(pending)
                .setOngoing(!complete && (indeterminate || progress < 100));

        if (!complete) {
            builder.setProgress(100, progress, indeterminate);
        }
        return builder.build();
    }

    private String cleanFileName(String value) {
        String cleaned = value
                .replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ")
                .replaceAll("\\s+", " ")
                .trim();
        if (cleaned.isEmpty()) {
            cleaned = "Animex episode";
        }
        if (cleaned.length() > 96) {
            cleaned = cleaned.substring(0, 96).trim();
        }
        return cleaned;
    }

    private String timestamp() {
        SimpleDateFormat format = new SimpleDateFormat(
                "yyyyMMdd-HHmmss",
                Locale.US);
        format.setTimeZone(TimeZone.getDefault());
        return format.format(new Date());
    }

    private String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName()
                : message;
    }

    private static final class Playlist {
        final URL url;
        final String[] lines;

        Playlist(URL url, String[] lines) {
            this.url = url;
            this.lines = lines;
        }
    }
}
