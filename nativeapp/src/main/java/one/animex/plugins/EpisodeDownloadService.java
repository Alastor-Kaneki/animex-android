package one.animex.plugins;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.provider.MediaStore;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
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
    private static final Pattern BANDWIDTH =
            Pattern.compile("(?:^|,)BANDWIDTH=(\\d+)");
    private static final Pattern MAP_URI =
            Pattern.compile("URI=\\\"([^\\\"]+)\\\"");

    private NotificationManager notifications;

    @Override
    public void onCreate() {
        super.onCreate();
        notifications = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "Episode downloads", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Animex Plugins episode download progress");
            notifications.createNotificationChannel(channel);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        final String url = intent.getStringExtra(EXTRA_URL);
        final String title = intent.getStringExtra(EXTRA_TITLE);
        final String referer = intent.getStringExtra(EXTRA_REFERER);
        final String userAgent = intent.getStringExtra(EXTRA_USER_AGENT);
        final String cookie = intent.getStringExtra(EXTRA_COOKIE);
        if (url == null || url.trim().isEmpty()) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        startForeground(NOTIFICATION_ID,
                notification("Preparing episode download", 0, true, false));
        new Thread(new Runnable() {
            @Override
            public void run() {
                download(startId, url,
                        title == null ? "Animex episode" : title,
                        referer, userAgent, cookie);
            }
        }, "AnimexEpisodeDownload").start();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void download(int startId, String url, String title,
                          String referer, String userAgent, String cookie) {
        Uri mediaUri = null;
        File legacyFile = null;
        try {
            boolean hlsHint = url.toLowerCase(Locale.ROOT).contains(".m3u8");
            String fileName = cleanFileName(title) + "-" + timestamp()
                    + (hlsHint ? ".ts" : extension(url));
            OutputTarget target = createTarget(fileName, hlsHint);
            mediaUri = target.uri;
            legacyFile = target.file;

            BufferedOutputStream output = new BufferedOutputStream(
                    target.output, BUFFER_SIZE);
            try {
                transfer(url, referer, userAgent, cookie, output);
                output.flush();
            } finally {
                output.close();
            }

            if (mediaUri != null && Build.VERSION.SDK_INT >= 29) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Video.Media.IS_PENDING, 0);
                getContentResolver().update(mediaUri, values, null, null);
            }
            String saved = mediaUri != null
                    ? "Saved to Movies/Animex Plugins"
                    : "Saved to " + legacyFile.getAbsolutePath();
            notifications.notify(NOTIFICATION_ID,
                    notification(saved, 100, false, true));
        } catch (Exception error) {
            if (mediaUri != null) getContentResolver().delete(mediaUri, null, null);
            if (legacyFile != null && legacyFile.exists()) legacyFile.delete();
            notifications.notify(NOTIFICATION_ID,
                    notification("Download failed: " + safeMessage(error),
                            0, false, false));
        } finally {
            stopForeground(false);
            stopSelf(startId);
        }
    }

    private OutputTarget createTarget(String fileName, boolean hls) throws IOException {
        String mime = hls ? "video/mp2t" : mime(fileName);
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, fileName);
            values.put(MediaStore.Video.Media.MIME_TYPE, mime);
            values.put(MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/Animex Plugins");
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
            Uri uri = getContentResolver().insert(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("Unable to create MediaStore file");
            OutputStream output = getContentResolver().openOutputStream(uri, "w");
            if (output == null) throw new IOException("Unable to open output file");
            return new OutputTarget(uri, null, output);
        }

        File base = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (base == null) throw new IOException("External storage is unavailable");
        File directory = new File(base, "Animex Plugins");
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IOException("Unable to create download directory");
        }
        File file = new File(directory, fileName);
        return new OutputTarget(null, file, new FileOutputStream(file));
    }

    private void transfer(String url, String referer, String userAgent,
                          String cookie, OutputStream output) throws IOException {
        HttpURLConnection connection = connect(url, referer, userAgent, cookie);
        try {
            String contentType = connection.getContentType();
            BufferedInputStream input = new BufferedInputStream(
                    checkedInput(connection), BUFFER_SIZE);
            try {
                input.mark(16);
                byte[] prefix = new byte[7];
                int prefixLength = input.read(prefix);
                input.reset();
                String prefixText = prefixLength <= 0 ? ""
                        : new String(prefix, 0, prefixLength, StandardCharsets.UTF_8);
                boolean hls = url.toLowerCase(Locale.ROOT).contains(".m3u8")
                        || (contentType != null
                        && contentType.toLowerCase(Locale.ROOT).contains("mpegurl"))
                        || prefixText.startsWith("#EXTM3U");
                if (hls) {
                    String manifest = readText(input, MAX_MANIFEST_BYTES);
                    downloadHls(connection.getURL(), manifest,
                            referer, userAgent, cookie, output, 0);
                } else {
                    copy(input, output);
                }
            } finally {
                input.close();
            }
        } finally {
            connection.disconnect();
        }
    }

    private void downloadHls(URL playlist, String manifest,
                             String referer, String userAgent, String cookie,
                             OutputStream output, int depth) throws IOException {
        if (depth > 3) throw new IOException("Too many nested HLS playlists");
        String[] lines = manifest.replace("\r", "").split("\n");
        URL variant = highestVariant(playlist, lines);
        if (variant != null) {
            downloadHls(variant,
                    fetchText(variant.toString(), referer, userAgent, cookie),
                    referer, userAgent, cookie, output, depth + 1);
            return;
        }

        List<URL> segments = new ArrayList<URL>();
        URL init = null;
        boolean encrypted = false;
        boolean byteRange = false;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith("#EXT-X-KEY:")) {
                String upper = line.toUpperCase(Locale.ROOT);
                if (!upper.contains("METHOD=NONE")) encrypted = true;
            } else if (line.startsWith("#EXT-X-BYTERANGE")) {
                byteRange = true;
            } else if (line.startsWith("#EXT-X-MAP:")) {
                Matcher matcher = MAP_URI.matcher(line);
                if (matcher.find()) init = new URL(playlist, matcher.group(1));
            } else if (!line.isEmpty() && !line.startsWith("#")) {
                segments.add(new URL(playlist, line));
            }
        }

        if (encrypted) throw new IOException("Encrypted HLS is not supported");
        if (byteRange) throw new IOException("Byte-range HLS is not supported");
        if (segments.isEmpty()) throw new IOException("HLS playlist contains no segments");

        if (init != null) downloadPart(init, referer, userAgent, cookie, output);
        for (int i = 0; i < segments.size(); i++) {
            downloadPart(segments.get(i), referer, userAgent, cookie, output);
            int progress = Math.max(1, Math.min(99, ((i + 1) * 100) / segments.size()));
            notifications.notify(NOTIFICATION_ID,
                    notification("Downloading episode", progress, false, false));
        }
    }

    private URL highestVariant(URL base, String[] lines) throws IOException {
        URL best = null;
        long bestBandwidth = -1;
        long pendingBandwidth = -1;
        for (String raw : lines) {
            String line = raw.trim();
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                Matcher matcher = BANDWIDTH.matcher(
                        line.substring("#EXT-X-STREAM-INF:".length()));
                pendingBandwidth = matcher.find()
                        ? Long.parseLong(matcher.group(1)) : 0;
            } else if (pendingBandwidth >= 0 &&
                    !line.isEmpty() && !line.startsWith("#")) {
                if (pendingBandwidth > bestBandwidth) {
                    bestBandwidth = pendingBandwidth;
                    best = new URL(base, line);
                }
                pendingBandwidth = -1;
            }
        }
        return best;
    }

    private void downloadPart(URL url, String referer, String userAgent,
                              String cookie, OutputStream output) throws IOException {
        HttpURLConnection connection = connect(url.toString(), referer, userAgent, cookie);
        try {
            InputStream input = checkedInput(connection);
            try { copy(input, output); }
            finally { input.close(); }
        } finally {
            connection.disconnect();
        }
    }

    private String fetchText(String url, String referer,
                             String userAgent, String cookie) throws IOException {
        HttpURLConnection connection = connect(url, referer, userAgent, cookie);
        try {
            InputStream input = checkedInput(connection);
            try { return readText(input, MAX_MANIFEST_BYTES); }
            finally { input.close(); }
        } finally {
            connection.disconnect();
        }
    }

    private HttpURLConnection connect(String url, String referer,
                                      String userAgent, String cookie) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(20_000);
        connection.setReadTimeout(60_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("Accept", "*/*");
        if (userAgent != null && !userAgent.trim().isEmpty())
            connection.setRequestProperty("User-Agent", userAgent);
        if (referer != null && !referer.trim().isEmpty())
            connection.setRequestProperty("Referer", referer);
        if (cookie != null && !cookie.trim().isEmpty())
            connection.setRequestProperty("Cookie", cookie);
        connection.connect();
        return connection;
    }

    private InputStream checkedInput(HttpURLConnection connection) throws IOException {
        int code = connection.getResponseCode();
        if (code < 200 || code >= 300) throw new IOException("HTTP " + code);
        return connection.getInputStream();
    }

    private void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        int read;
        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
    }

    private String readText(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > limit) throw new IOException("HLS manifest is too large");
            bytes.write(buffer, 0, read);
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    private Notification notification(String text, int progress,
                                      boolean indeterminate, boolean complete) {
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        Intent open = new Intent(this, MainActivity.class);
        int pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) pendingFlags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pending = PendingIntent.getActivity(this, 0, open, pendingFlags);
        builder.setSmallIcon(complete
                        ? android.R.drawable.stat_sys_download_done
                        : android.R.drawable.stat_sys_download)
                .setContentTitle(complete
                        ? "Animex episode downloaded" : "Animex episode download")
                .setContentText(text)
                .setOnlyAlertOnce(true)
                .setContentIntent(pending)
                .setOngoing(!complete && (indeterminate || progress < 100));
        if (!complete) builder.setProgress(100, progress, indeterminate);
        return builder.build();
    }

    private String extension(String url) {
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.contains(".webm")) return ".webm";
        if (lower.contains(".m4v")) return ".m4v";
        return ".mp4";
    }

    private String mime(String fileName) {
        String lower = fileName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".webm")) return "video/webm";
        if (lower.endsWith(".m4v")) return "video/x-m4v";
        return "video/mp4";
    }

    private String cleanFileName(String value) {
        String cleaned = value
                .replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ")
                .replaceAll("\\s+", " ").trim();
        if (cleaned.isEmpty()) cleaned = "Animex episode";
        return cleaned.length() > 96 ? cleaned.substring(0, 96).trim() : cleaned;
    }

    private String timestamp() {
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US);
        format.setTimeZone(TimeZone.getDefault());
        return format.format(new Date());
    }

    private String safeMessage(Exception error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? error.getClass().getSimpleName() : message;
    }

    private static final class OutputTarget {
        final Uri uri;
        final File file;
        final OutputStream output;

        OutputTarget(Uri uri, File file, OutputStream output) {
            this.uri = uri;
            this.file = file;
            this.output = output;
        }
    }
}
