package com.ttl.tabletennis.cv;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

public interface VlmHttpExchange {

    Response post(String url, Map<String, String> headers, String body, Duration timeout) throws IOException, InterruptedException;

    record Response(int statusCode, String body) { }
}
