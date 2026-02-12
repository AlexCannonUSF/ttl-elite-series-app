package com.ttl.tabletennis.scrape;

import com.ttl.tabletennis.model.MatchOdds;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

/**
 * Pulls public odds from Hard Rock Bet.
 * If you discover a public JSON endpoint in DevTools, pass it via -Dhr.json=<url>.
 * Otherwise, it tries a best-effort parse of the public page HTML.
 * (No login is attempted.)
 */
public class HardRockOddsScraper {
    private final String home = "https://app.hardrock.bet/home";
    private final String json = System.getProperty("hr.json", "");

    public List<MatchOdds> fetch() {
        if (!json.isBlank()) {
            try {
                HttpClient c = HttpClient.newHttpClient();
                HttpRequest req = HttpRequest.newBuilder(URI.create(json))
                        .header("accept", "application/json").build();
                HttpResponse<String> resp = c.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    return parseJson(resp.body());
                }
            } catch (Exception ignore) {}
        }
        return scrapeHtmlFallback();
    }

    private List<MatchOdds> parseJson(String body) {
        // Minimal, schema-agnostic pattern; replace with proper JSON parsing once endpoint is known.
        var list = new ArrayList<MatchOdds>();
        var m = java.util.regex.Pattern.compile(
                "\"playerA\"\\s*:\\s*\"([^\"]+)\".*?\"playerB\"\\s*:\\s*\"([^\"]+)\".*?\"oddsA\"\\s*:\\s*([0-9.]+).*?\"oddsB\"\\s*:\\s*([0-9.]+)",
                java.util.regex.Pattern.DOTALL).matcher(body);
        while (m.find()) list.add(new MatchOdds(m.group(1), m.group(2),
                Double.parseDouble(m.group(3)), Double.parseDouble(m.group(4))));
        return list;
    }

    private List<MatchOdds> scrapeHtmlFallback() {
        var out = new ArrayList<MatchOdds>();
        try {
            Document doc = Jsoup.connect(home).userAgent("Mozilla/5.0").timeout(20000).get();
            // Narrow scope to Table Tennis / TT Elite Series blocks where possible
            for (Element row : doc.select("[role=row]")) {
                String text = row.text();
                // Look for "... Player A ... Player B ... +160  -210" patterns (American odds)
                var mm = java.util.regex.Pattern.compile(
                                "([A-Za-zÀ-ž'\\-]+\\s+[A-Za-zÀ-ž'\\-]+).*?([A-Za-zÀ-ž'\\-]+\\s+[A-Za-zÀ-ž'\\-]+).*?([+\\-]\\d+)\\s+([+\\-]\\d+)")
                        .matcher(text);
                if (mm.find()) {
                    String a = mm.group(1), b = mm.group(2);
                    double oa = americanToDecimal(mm.group(3));
                    double ob = americanToDecimal(mm.group(4));
                    out.add(new MatchOdds(a, b, oa, ob));
                }
            }
        } catch (Exception ignore) {}
        return out;
    }

    private double americanToDecimal(String american) {
        double x = Double.parseDouble(american.trim());
        if (x > 0) return 1.0 + x / 100.0;
        return 1.0 + 100.0 / Math.abs(x);
    }
}