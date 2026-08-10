package top.weixiansen574.hybridfilexfer.share;

import android.content.Context;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import top.weixiansen574.hybridfilexfer.R;

/** Small, dependency-free HTTP server for offline browser downloads. */
final class WebShareServer {
    private static final int MAX_HEADER_LINES = 80;
    private static final int MAX_LINE_BYTES = 8 * 1024;
    private static final int SOCKET_TIMEOUT_MS = 20_000;
    private static final int WRITE_IDLE_TIMEOUT_MS = 30_000;
    private static final int COPY_BUFFER_SIZE = 64 * 1024;

    private final Context context;
    private final List<SharedFile> files;
    private final String token;
    private final int preferredPort;
    private final ExecutorService clients = new ThreadPoolExecutor(
            4, 4, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(16),
            new ThreadPoolExecutor.AbortPolicy());
    private final ConcurrentHashMap<Socket, ClientState> activeSockets =
            new ConcurrentHashMap<>();
    private final ScheduledExecutorService watchdog =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "WebShare-Watchdog");
                thread.setDaemon(true);
                return thread;
            });
    private volatile boolean running;
    private ServerSocket serverSocket;
    private Thread acceptThread;

    WebShareServer(Context context, List<SharedFile> files, String token, int preferredPort) {
        this.context = context.getApplicationContext();
        this.files = new ArrayList<>(files);
        this.token = token;
        this.preferredPort = preferredPort;
    }

    int start() throws IOException {
        serverSocket = bindAvailablePort(preferredPort);
        running = true;
        watchdog.scheduleWithFixedDelay(this::closeStalledClients,
                5, 5, TimeUnit.SECONDS);
        acceptThread = new Thread(this::acceptLoop, "WebShare-Accept");
        acceptThread.start();
        return serverSocket.getLocalPort();
    }

    private static ServerSocket bindAvailablePort(int preferredPort) throws IOException {
        IOException lastError = null;
        for (int port = preferredPort; port < preferredPort + 10; port++) {
            ServerSocket candidate = new ServerSocket();
            candidate.setReuseAddress(true);
            try {
                candidate.bind(new InetSocketAddress((InetAddress) null, port), 16);
                return candidate;
            } catch (IOException e) {
                lastError = e;
                try {
                    candidate.close();
                } catch (IOException ignored) {
                }
            }
        }
        throw lastError == null ? new IOException("No available share port") : lastError;
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                socket.setSoTimeout(SOCKET_TIMEOUT_MS);
                socket.setTcpNoDelay(true);
                ClientState state = new ClientState(socket);
                activeSockets.put(socket, state);
                try {
                    clients.execute(() -> handleClient(state));
                } catch (RuntimeException e) {
                    activeSockets.remove(socket);
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                    }
                }
            } catch (SocketException e) {
                if (running) {
                    e.printStackTrace();
                }
                break;
            } catch (IOException e) {
                if (running) {
                    e.printStackTrace();
                }
            } catch (RuntimeException e) {
                if (running) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void handleClient(ClientState state) {
        Socket socket = state.socket;
        try (Socket ignored = socket;
             InputStream rawInput = new BufferedInputStream(socket.getInputStream());
             OutputStream output = new BufferedOutputStream(
                     new ProgressOutputStream(socket.getOutputStream(), state))) {
            Request request = readRequest(rawInput);
            if (request == null) {
                return;
            }
            if (!"GET".equals(request.method) && !"HEAD".equals(request.method)) {
                sendText(output, 405, "Method Not Allowed", "text/plain; charset=utf-8",
                        "Method not allowed", request.headOnly());
                return;
            }
            String cleanPath = request.path;
            int queryIndex = cleanPath.indexOf('?');
            if (queryIndex >= 0) {
                cleanPath = cleanPath.substring(0, queryIndex);
            }
            String root = "/s/" + token;
            if (cleanPath.equals(root)) {
                sendRedirect(output, root + "/");
            } else if (cleanPath.equals(root + "/")) {
                sendIndex(output, request.headOnly());
            } else if (cleanPath.equals(root + "/all.zip")) {
                sendZip(output, request.headOnly());
            } else if (cleanPath.startsWith(root + "/file/")) {
                sendFile(output, request, cleanPath.substring((root + "/file/").length()));
            } else {
                sendText(output, 404, "Not Found", "text/plain; charset=utf-8",
                        "Not found", request.headOnly());
            }
        } catch (IOException | RuntimeException ignored) {
            // The receiver may leave Wi-Fi or cancel a download. The session stays alive.
        } finally {
            activeSockets.remove(socket);
        }
    }

    private static Request readRequest(InputStream input) throws IOException {
        String requestLine = readLineLimited(input);
        if (requestLine == null) {
            return null;
        }
        String[] parts = requestLine.split(" ", 3);
        if (parts.length != 3) {
            return null;
        }
        String range = null;
        boolean complete = false;
        for (int i = 0; i < MAX_HEADER_LINES; i++) {
            String line = readLineLimited(input);
            if (line == null) {
                return null;
            }
            if (line.isEmpty()) {
                complete = true;
                break;
            }
            int colon = line.indexOf(':');
            if (colon > 0 && "range".equalsIgnoreCase(line.substring(0, colon).trim())) {
                if (range != null) {
                    return null;
                }
                range = line.substring(colon + 1).trim();
            }
        }
        return complete ? new Request(parts[0], parts[1], range) : null;
    }

    private static String readLineLimited(InputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(128);
        while (true) {
            int value = input.read();
            if (value == -1) {
                if (line.size() == 0) {
                    return null;
                }
                break;
            }
            if (value == '\n') {
                break;
            }
            if (line.size() >= MAX_LINE_BYTES) {
                throw new IOException("HTTP line too long");
            }
            line.write(value);
        }
        byte[] bytes = line.toByteArray();
        int length = bytes.length;
        if (length > 0 && bytes[length - 1] == '\r') {
            length--;
        }
        return new String(bytes, 0, length, StandardCharsets.ISO_8859_1);
    }

    private void sendIndex(OutputStream output, boolean headOnly) throws IOException {
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < files.size(); i++) {
            SharedFile file = files.get(i);
            list.append("<a class=\"file\" href=\"file/").append(i).append("\">")
                    .append("<span class=\"name\">").append(escapeHtml(file.name)).append("</span>")
                    .append("<span class=\"size\">").append(formatBytes(file.size)).append("</span>")
                    .append("</a>");
        }
        String zip = files.size() > 1
                ? "<a class=\"all\" href=\"all.zip\">" + escapeHtml(context.getString(R.string.web_download_all)) + "</a>"
                : "";
        String html = "<!doctype html><html lang=\"" + Locale.getDefault().getLanguage() + "\"><head>"
                + "<meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>" + escapeHtml(context.getString(R.string.web_receiver_title)) + "</title>"
                + "<style>:root{color-scheme:light dark}*{box-sizing:border-box}body{margin:0;font-family:system-ui,sans-serif;background:#f5f7fb;color:#172033}main{max-width:680px;margin:auto;padding:32px 18px}.hero{padding:24px;border-radius:24px;background:linear-gradient(135deg,#3157d5,#4f7cff);color:white;box-shadow:0 14px 36px #2447a733}.hero h1{margin:0 0 8px;font-size:28px}.hero p{margin:0;opacity:.86}.files{margin-top:18px;display:grid;gap:10px}.file,.all{display:flex;justify-content:space-between;gap:18px;align-items:center;padding:17px 18px;border-radius:16px;background:white;color:#172033;text-decoration:none;box-shadow:0 5px 18px #23304c12}.file:active,.all:active{transform:scale(.985)}.name{font-weight:650;overflow-wrap:anywhere}.size{white-space:nowrap;color:#65718a}.all{justify-content:center;background:#172033;color:white;font-weight:700;margin-top:16px}.foot{text-align:center;color:#65718a;font-size:13px;margin-top:24px}@media(prefers-color-scheme:dark){body{background:#10131a;color:#edf1ff}.file{background:#1b202b;color:#edf1ff}.all{background:#edf1ff;color:#10131a}}</style>"
                + "</head><body><main><section class=\"hero\"><h1>"
                + escapeHtml(context.getString(R.string.web_receiver_title)) + "</h1><p>"
                + escapeHtml(context.getString(R.string.web_receiver_subtitle, files.size()))
                + "</p></section><section class=\"files\">" + list + "</section>" + zip
                + "<p class=\"foot\">" + escapeHtml(context.getString(R.string.web_receiver_footer))
                + "</p></main></body></html>";
        sendText(output, 200, "OK", "text/html; charset=utf-8", html, headOnly);
    }

    private void sendFile(OutputStream output, Request request, String indexText) throws IOException {
        int index;
        try {
            index = Integer.parseInt(indexText);
        } catch (NumberFormatException e) {
            sendText(output, 404, "Not Found", "text/plain; charset=utf-8",
                    "Not found", request.headOnly());
            return;
        }
        if (index < 0 || index >= files.size()) {
            sendText(output, 404, "Not Found", "text/plain; charset=utf-8",
                    "Not found", request.headOnly());
            return;
        }
        SharedFile file = files.get(index);
        InputStream openedInput = null;
        if (!request.headOnly()) {
            try {
                openedInput = context.getContentResolver().openInputStream(file.uri);
            } catch (IOException | RuntimeException ignored) {
            }
            if (openedInput == null) {
                sendText(output, 410, "Gone", "text/plain; charset=utf-8",
                        "This file is no longer available", false);
                return;
            }
        }
        try {
        ByteRange range = ByteRange.parse(request.range, file.size);
        if (range == ByteRange.INVALID) {
            closeQuietly(openedInput);
            writeStatus(output, 416, "Range Not Satisfiable");
            writeCommonHeaders(output);
            writeHeader(output, "Content-Range", "bytes */" + file.size);
            writeHeader(output, "Content-Length", "0");
            finishHeaders(output);
            output.flush();
            return;
        }
        long start = range == null ? 0 : range.start;
        long end = range == null ? file.size - 1 : range.end;
        long length = file.size >= 0 ? end - start + 1 : -1;
        writeStatus(output, range == null ? 200 : 206, range == null ? "OK" : "Partial Content");
        writeCommonHeaders(output);
        writeHeader(output, "Content-Type", file.mimeType);
        writeHeader(output, "Content-Disposition", contentDisposition(file.name));
        if (file.size >= 0) {
            writeHeader(output, "Accept-Ranges", "bytes");
            writeHeader(output, "Content-Length", String.valueOf(length));
            if (range != null) {
                writeHeader(output, "Content-Range", "bytes " + start + "-" + end + "/" + file.size);
            }
        }
        finishHeaders(output);
        if (request.headOnly()) {
            return;
        }
        try (InputStream input = openedInput) {
            skipFully(input, start);
            copy(input, output, length);
        }
        } finally {
            closeQuietly(openedInput);
        }
    }

    private void sendZip(OutputStream output, boolean headOnly) throws IOException {
        writeStatus(output, 200, "OK");
        writeCommonHeaders(output);
        writeHeader(output, "Content-Type", "application/zip");
        writeHeader(output, "Content-Disposition", contentDisposition("shared-files.zip"));
        finishHeaders(output);
        if (headOnly) {
            return;
        }
        Set<String> usedNames = new HashSet<>();
        ZipOutputStream zip = new ZipOutputStream(output);
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        for (SharedFile file : files) {
            String entryName = uniqueZipName(file.name, usedNames);
            zip.putNextEntry(new ZipEntry(entryName));
            try (InputStream input = context.getContentResolver().openInputStream(file.uri)) {
                if (input != null) {
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        if (count == 0) {
                            int value = input.read();
                            if (value == -1) {
                                break;
                            }
                            zip.write(value);
                        } else {
                            zip.write(buffer, 0, count);
                        }
                    }
                }
            } catch (IOException | RuntimeException ignored) {
                // Keep the ZIP structurally valid if a cloud-backed URI disappears.
            }
            zip.closeEntry();
        }
        zip.finish();
        zip.flush();
    }

    private static String uniqueZipName(String requested, Set<String> used) {
        String candidate = requested;
        int counter = 2;
        while (!used.add(candidate.toLowerCase(Locale.ROOT))) {
            int dot = requested.lastIndexOf('.');
            candidate = dot > 0
                    ? requested.substring(0, dot) + " (" + counter + ")" + requested.substring(dot)
                    : requested + " (" + counter + ")";
            counter++;
        }
        return candidate;
    }

    private static void copy(InputStream input, OutputStream output, long remaining) throws IOException {
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        while (remaining != 0) {
            int wanted = remaining < 0 ? buffer.length : (int) Math.min(buffer.length, remaining);
            int count = input.read(buffer, 0, wanted);
            if (count == -1) {
                break;
            }
            if (count == 0) {
                int value = input.read();
                if (value == -1) {
                    break;
                }
                output.write(value);
                count = 1;
            } else {
                output.write(buffer, 0, count);
            }
            if (remaining > 0) {
                remaining -= count;
            }
        }
        output.flush();
    }

    private static void skipFully(InputStream input, long byteCount) throws IOException {
        long remaining = byteCount;
        while (remaining > 0) {
            long skipped = input.skip(remaining);
            if (skipped > 0) {
                remaining -= skipped;
            } else if (input.read() == -1) {
                throw new IOException("Unexpected end of file");
            } else {
                remaining--;
            }
        }
    }

    private static void closeQuietly(InputStream input) {
        if (input != null) {
            try {
                input.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static void sendText(OutputStream output, int status, String reason,
                                 String contentType, String text, boolean headOnly) throws IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        writeStatus(output, status, reason);
        writeCommonHeaders(output);
        writeHeader(output, "Content-Type", contentType);
        writeHeader(output, "Content-Length", String.valueOf(body.length));
        if (contentType.startsWith("text/html")) {
            writeHeader(output, "Content-Security-Policy",
                    "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; frame-ancestors 'none'");
        }
        finishHeaders(output);
        if (!headOnly) {
            output.write(body);
        }
        output.flush();
    }

    private static void sendRedirect(OutputStream output, String location) throws IOException {
        writeStatus(output, 302, "Found");
        writeCommonHeaders(output);
        writeHeader(output, "Location", location);
        writeHeader(output, "Content-Length", "0");
        finishHeaders(output);
        output.flush();
    }

    private static void writeStatus(OutputStream output, int status, String reason) throws IOException {
        output.write(("HTTP/1.1 " + status + " " + reason + "\r\n")
                .getBytes(StandardCharsets.ISO_8859_1));
    }

    private static void writeCommonHeaders(OutputStream output) throws IOException {
        writeHeader(output, "Connection", "close");
        writeHeader(output, "Cache-Control", "no-store");
        writeHeader(output, "X-Content-Type-Options", "nosniff");
        writeHeader(output, "Referrer-Policy", "no-referrer");
    }

    private static void writeHeader(OutputStream output, String name, String value) throws IOException {
        output.write((name + ": " + value + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
    }

    private static void finishHeaders(OutputStream output) throws IOException {
        output.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
    }

    private static String contentDisposition(String filename) {
        String fallback = filename.replaceAll("[^A-Za-z0-9._ -]", "_").replace('"', '_');
        String encoded;
        try {
            encoded = URLEncoder.encode(filename, "UTF-8").replace("+", "%20");
        } catch (Exception impossible) {
            encoded = fallback;
        }
        return "attachment; filename=\"" + fallback + "\"; filename*=UTF-8''" + encoded;
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String formatBytes(long bytes) {
        if (bytes < 0) {
            return "—";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes;
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        int unit = 0;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.getDefault(), "%.1f %s", value, units[unit]);
    }

    void stop() {
        running = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
        }
        for (Socket socket : activeSockets.keySet()) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
        activeSockets.clear();
        clients.shutdownNow();
        watchdog.shutdownNow();
    }

    private void closeStalledClients() {
        long now = System.nanoTime();
        long limit = TimeUnit.MILLISECONDS.toNanos(WRITE_IDLE_TIMEOUT_MS);
        for (ClientState state : activeSockets.values()) {
            if (now - state.lastProgressNanos > limit) {
                try {
                    state.socket.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static final class ClientState {
        final Socket socket;
        volatile long lastProgressNanos = System.nanoTime();

        ClientState(Socket socket) {
            this.socket = socket;
        }

        void progressed() {
            lastProgressNanos = System.nanoTime();
        }
    }

    private static final class ProgressOutputStream extends OutputStream {
        private final OutputStream delegate;
        private final ClientState state;

        ProgressOutputStream(OutputStream delegate, ClientState state) {
            this.delegate = delegate;
            this.state = state;
        }

        @Override
        public void write(int value) throws IOException {
            delegate.write(value);
            state.progressed();
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            delegate.write(bytes, offset, length);
            state.progressed();
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
            state.progressed();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }
    }

    private static final class Request {
        final String method;
        final String path;
        final String range;

        Request(String method, String path, String range) {
            this.method = method;
            this.path = path;
            this.range = range;
        }

        boolean headOnly() {
            return "HEAD".equals(method);
        }
    }

    static final class ByteRange {
        static final ByteRange INVALID = new ByteRange(-1, -1);
        final long start;
        final long end;

        ByteRange(long start, long end) {
            this.start = start;
            this.end = end;
        }

        static ByteRange parse(String header, long size) {
            if (header == null || header.isEmpty() || size < 0) {
                return null;
            }
            if (size == 0) {
                return INVALID;
            }
            if (!header.regionMatches(true, 0, "bytes=", 0, 6) || header.indexOf(',') >= 0) {
                return INVALID;
            }
            String value = header.substring(6).trim();
            int dash = value.indexOf('-');
            if (dash < 0) {
                return INVALID;
            }
            try {
                if (dash == 0) {
                    long suffix = Long.parseLong(value.substring(1));
                    if (suffix <= 0) {
                        return INVALID;
                    }
                    suffix = Math.min(suffix, size);
                    return new ByteRange(size - suffix, size - 1);
                }
                long start = Long.parseLong(value.substring(0, dash));
                long end = dash == value.length() - 1
                        ? size - 1 : Long.parseLong(value.substring(dash + 1));
                if (start < 0 || start >= size || end < start) {
                    return INVALID;
                }
                return new ByteRange(start, Math.min(end, size - 1));
            } catch (NumberFormatException e) {
                return INVALID;
            }
        }
    }
}
