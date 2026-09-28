/*
 * Local preview of the GitHub Pages demo build.
 *
 * GitHub Pages serves the site from https://<user>.github.io/UniPay/, i.e. from
 * a subpath, with js/config.js rewritten to demo mode. Serving the assets over a
 * plain file server is not the same thing, so this tool reproduces both: it
 * mounts src/main/resources/static under a configurable subpath and overrides
 * config.js, which is exactly what .github/workflows/pages.yml does.
 *
 * It exists so the Pages build can be checked before pushing. It is a preview
 * tool only — nothing in the Spring Boot app depends on it.
 *
 * Run from the project root:
 *   java tools/DemoServer.java
 *   java tools/DemoServer.java 8099 /UniPay
 * Then open http://localhost:8099/UniPay/
 */
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

public final class DemoServer {

    private static final Map<String, String> MIME = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"),
            Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("json", "application/json; charset=utf-8"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("woff2", "font/woff2"));

    /** Mirrors the override the Pages workflow applies. */
    private static final String DEMO_CONFIG =
            "/* Overridden by the local Pages preview: offline demo backend. */\n"
            + "window.UNIPAY_CONFIG = { demo: true, apiBase: '' };\n";

    public static void main(String[] args) throws IOException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8099;
        String raw = args.length > 1 ? args[1] : "/UniPay";
        if (!raw.startsWith("/")) raw = "/" + raw;
        final String mount = raw.replaceAll("/+$", "");

        Path root = Path.of("src", "main", "resources", "static").toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            System.err.println("Cannot find " + root + " — run this from the project root.");
            System.exit(1);
        }

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext(mount, ex -> handle(ex, root, mount));
        // Anything outside the mount is the real API, which this server has none of.
        server.createContext("/", ex -> {
            byte[] msg = ("This is the static Pages preview.\nOpen /" + mount.substring(1)
                    + "/ instead — the demo backend is in-memory, so there is no API here.")
                    .getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
            ex.sendResponseHeaders(404, msg.length);
            ex.getResponseBody().write(msg);
            ex.close();
        });
        server.setExecutor(null);
        server.start();

        System.out.println("UniPay demo preview:  http://localhost:" + port + mount + "/");
        System.out.println("Serving " + root + " with demo: true");
    }

    private static void handle(HttpExchange ex, Path root, String mount) throws IOException {
        try {
            String rel = ex.getRequestURI().getPath().substring(mount.length());
            if (rel.isEmpty() || rel.equals("/")) rel = "/index.html";

            // Resolve inside the root and refuse anything that escapes it.
            Path file = root.resolve(rel.substring(1)).normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file)) {
                notFound(ex);
                return;
            }

            if (file.getFileName().toString().equals("config.js")) {
                byte[] body = DEMO_CONFIG.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", MIME.get("js"));
                ex.getResponseHeaders().add("Cache-Control", "no-store");
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
                ex.close();
                return;
            }

            String ext = fileNameExtension(file.getFileName().toString());
            byte[] body = Files.readAllBytes(file);
            ex.getResponseHeaders().add("Content-Type", MIME.getOrDefault(ext, "application/octet-stream"));
            ex.getResponseHeaders().add("Cache-Control", "no-store");
            ex.sendResponseHeaders(200, body.length);
            try (OutputStream out = ex.getResponseBody()) {
                out.write(body);
            }
        } catch (IOException e) {
            ex.close();
        }
    }

    private static String fileNameExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static void notFound(HttpExchange ex) throws IOException {
        byte[] msg = "Not found".getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(404, msg.length);
        try (InputStream in = new java.io.ByteArrayInputStream(msg);
             OutputStream out = ex.getResponseBody()) {
            out.write(in.readAllBytes());
        }
    }
}
