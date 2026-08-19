package com.ttl.tabletennis.prediction.shadow;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

public interface BlenderHttpExchange {

    Response post(String url, Map<String, String> headers, String body, Duration timeout) throws IOException, InterruptedException;

    record Response(int statusCode, String body) { }
}
