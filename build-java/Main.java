import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Logger;

public class Main {

    public static final String REQUEST_ID_KEY = "requestId";

    public static void main(String[] args) throws IOException, InterruptedException {
        InetSocketAddress listenAddr = args.length > 0
                ? new InetSocketAddress(args[0].split(":")[0], Integer.parseInt(args[0].split(":")[1]))
                : new InetSocketAddress(5000);

        Logger logger = Logger.getLogger("http");
        logger.info("Server is starting...");

        AtomicBoolean healthy = new AtomicBoolean(true);

        Supplier<String> nextRequestId = () -> Long.toString(System.nanoTime());

        HttpServer server = HttpServer.create(listenAddr, 0);
        List<Filter> filters = Arrays.asList(tracing(nextRequestId), logging(logger));
        server.createContext("/", index()).getFilters().addAll(filters);
        server.createContext("/healthz", healthz(healthy)).getFilters().addAll(filters);
        ExecutorService executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("Server is shutting down...");
            healthy.set(false);
            server.stop(5);
            logger.info("Server stopped");
        }));

        logger.info("Server is ready to handle requests at " + listenAddr);
        healthy.set(true);
        server.start();
    }

    private static HttpHandler index() {
        return http -> {
            if (!http.getRequestURI().getPath().equals("/")) {
                http.sendResponseHeaders(HttpURLConnection.HTTP_NOT_FOUND, -1);
                return;
            }
            http.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
            http.getResponseHeaders().add("X-Content-Type-Options", "nosniff");
            http.sendResponseHeaders(HttpURLConnection.HTTP_OK, 0);
            http.getResponseBody().write("Hello, World!\n".getBytes());
            http.close();
        };
    }

    private static HttpHandler healthz(AtomicBoolean healthy) {
        return http -> {
            if (healthy.get()) http.sendResponseHeaders(HttpURLConnection.HTTP_NO_CONTENT, -1);
            else http.sendResponseHeaders(HttpURLConnection.HTTP_UNAVAILABLE, -1);
        };
    }

    private static Filter logging(Logger logger) {
        return new Filter() {
            @Override
            public void doFilter(HttpExchange http, Chain chain) throws IOException {
                try {
                    chain.doFilter(http);
                } finally {
                    Object possibleRequestId = http.getAttribute(REQUEST_ID_KEY);
                    String requestId = possibleRequestId instanceof String ? (String) possibleRequestId : "unknown";
                    logger.info(String.format("%s %s %s %s %s",
                                              requestId,
                                              http.getRequestMethod(),
                                              http.getRequestURI().getPath(),
                                              http.getRemoteAddress(),
                                              http.getRequestHeaders().getFirst("User-Agent")));
                }
            }

            @Override
            public String description() {
                return "logging";
            }
        };
    }

    private static Filter tracing(Supplier<String> nextRequestId) {
        return new Filter() {
            @Override
            public void doFilter(HttpExchange http, Chain chain) throws IOException {
                String requestId = http.getRequestHeaders().getFirst("X-Request-Id");
                if (requestId == null) requestId = nextRequestId.get();
                http.setAttribute(REQUEST_ID_KEY, requestId);
                http.getResponseHeaders().add("X-Request-Id", requestId);
                chain.doFilter(http);
            }

            @Override
            public String description() {
                return "tracing";
            }
        };
    }

}