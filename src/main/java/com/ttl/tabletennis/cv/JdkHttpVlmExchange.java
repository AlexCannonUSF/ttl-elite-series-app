package com.ttl.tabletennis.cv;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

public class JdkHttpVlmExchange implements VlmHttpExchange {

    private final HttpClient httpClient;

    public JdkHttpVlmExchange() {
        this(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(5))
                .build());
    }

    public JdkHttpVlmExchange(HttpClient httpClient) {
        if (httpClient == null) {
            throw new IllegalArgumentException("httpClient must not be null");
        }
        this.httpClient = httpClient;
    }

    @Override
    public Response post(String url,
                         Map<String, String> headers,
                         String body,
                         Duration timeout) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .header("Content-Type", "application/json");
        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                builder.header(entry.getKey(), entry.getValue());
            }
        }
        HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new Response(response.statusCode(), response.body());
    }
}
