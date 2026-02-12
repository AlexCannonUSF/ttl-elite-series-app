package com.ttl.tabletennis.scrape;

import com.ttl.tabletennis.Entity.Match;
import com.ttl.tabletennis.Entity.Player;
import com.ttl.tabletennis.Repository.MatchRepository;
import com.ttl.tabletennis.Repository.PlayerRepository;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Component
public class TtSeriesScraper {

    private static final Logger log = LoggerFactory.getLogger(TtSeriesScraper.class);

    private final MatchRepository matchRepository;
    private final PlayerRepository playerRepository;

    // --------- configurable knobs (via -D or application.properties) ----------
    @Value("${ttl.baseUrl:https://www.tt-series.com}")
    private String baseUrl;

    @Value("${ttl.listPath:/category/turnieje}")
    private String listPath;

    @Value("${ttl.startPage:1}")
    private int startPage;

    @Value("${ttl.endPage:1}")
    private int endPage;

    /** If set, scrape only that post id (e.g. 4741) */
    @Value("${ttl.onlyId:}")
    private String onlyId;

    /** Contract: ~10 "post" links per list page */
    @Value("${ttl.linksPerPage:10}")
    private int expectedLinksPerListPage;

    /** Contract: ~12 matches per post */
    @Value("${ttl.matchesPerPost:12}")
    private int expectedMatchesPerPost;

    /** polite delay between HTTP calls (ms) */
    @Value("${ttl.delayBetweenRequestsMs:350}")
    private long delayBetweenRequestsMs;

    @Value("${scrape.auto:true}")
    private boolean auto;

    // --------- parsing selectors (adjust only if the site changes) ----------
    // CSS for post links on the list pages
    @Value("${ttl.css.postLinks:.elementor-post__thumbnail__link, a.elementor-post__read-more, a.elementor-post__title__link}")
    private String cssPostLinks;

    // CSS for a single post page: where the match table lives
    @Value("${ttl.css.matchRows:table tbody tr}")
    private String cssMatchRows;

    // Column indexes within a match row (0-based)
    @Value("${ttl.col.player1:0}") private int colPlayer1;
    @Value("${ttl.col.player2:1}") private int colPlayer2;
    @Value("${ttl.col.result:2}")  private int colResult;
    @Value("${ttl.col.date:3}")    private int colDate;
    // ------------------------------------------------------------------------

    private final DateTimeFormatter dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public TtSeriesScraper(MatchRepository matchRepository,
                           PlayerRepository playerRepository) {
        this.matchRepository = matchRepository;
        this.playerRepository = playerRepository;
    }

    // ======== Back-compat wrapper methods (expected by existing code) ========

    /** Old entry point used via method reference (e.g., scraper::run). */
    public void run() {
        runAuto();
    }

    /** Old name used by FxApp. */
    public void autoScrape() {
        runAuto();
    }

    /** Old controller endpoint used to scrape one post by numeric id. */
    public void scrapePost(int id) {
        try {
            scrapeSinglePost(String.valueOf(id));
        } catch (Exception e) {
            log.warn("[scrape] scrapePost({}) failed: {}", id, e.getMessage(), e);
        }
    }

    /**
     * Old DesktopUI step 1: collect post links for a page range.
     * Returns unique absolute URLs.
     */
    public List<String> scrapeMatchLinks(int fromPage, int toPage) throws IOException, InterruptedException {
        List<String> all = new ArrayList<>();
        for (int page = fromPage; page <= toPage; page++) {
            String listUrl = buildListPageUrl(page);
            Document listDoc = fetch(listUrl);
            all.addAll(extractPostUrls(listDoc));
            politePause();
        }
        return new ArrayList<>(new LinkedHashSet<>(all));
    }

    /**
     * Old DesktopUI step 2: given post URLs, parse and persist matches.
     */
    // REPLACE the whole method in TtSeriesScraper with this version:
    public int scrapeAndSaveMatchDetails(List<String> postUrls) throws IOException {
        if (postUrls == null || postUrls.isEmpty()) return 0;

        int savedTotal = 0;
        int i = 0;

        for (String postUrl : postUrls) {
            i++;
            String postId = externalIdFromUrl(postUrl);

            // Fetch & parse this post
            Document postDoc = fetch(postUrl);
            List<MatchRow> rows = parseMatchRows(postDoc);

            int savedHere = 0;
            for (MatchRow row : rows) {
                if (upsertMatch(postId, row)) {
                    savedHere++;
                }
            }

            savedTotal += savedHere;
            log.info("[scrape] {}: saved {} match(es) from {}", i + "/" + postUrls.size(), savedHere, postId);

            politePause();
        }

        log.info("[scrape] BULK URLS DONE — total saved: {}", savedTotal);
        return savedTotal;
    }
    // ========================= Main orchestration ============================
    /** Kick off automatically on boot if enabled. */
    @Async("ttlScraperExecutor")
    public void runAuto() {
        if (!auto) {
            log.info("[scrape] auto=false -> idle");
            return;
        }
        try {
            if (StringUtils.hasText(onlyId)) {
                scrapeSinglePost(onlyId);
            } else {
                scrapePageRange(startPage, endPage);
            }
        } catch (Exception e) {
            log.warn("[scrape] FAILED: {}", e.getMessage(), e);
        }
    }

    public void scrapePageRange(int fromPage, int toPage) throws IOException, InterruptedException {
        final int pages = Math.max(0, toPage - fromPage + 1);
        int staticExpected = pages * expectedLinksPerListPage * expectedMatchesPerPost;
        ProgressTracker tracker = ProgressTracker.create(staticExpected);

        log.info("[scrape] begin pages {}..{} ({} pages). Expected ≈ {} matches ({} links/page × {} matches/post)",
                fromPage, toPage, pages, staticExpected, expectedLinksPerListPage, expectedMatchesPerPost);

        for (int page = fromPage; page <= toPage; page++) {
            String listUrl = buildListPageUrl(page);
            Document listDoc = fetch(listUrl);

            List<String> postUrls = extractPostUrls(listDoc);
            int foundLinks = postUrls.size();

            if (foundLinks != expectedLinksPerListPage) {
                log.warn("[scrape] page {}: found {} links (expected ~{})", page, foundLinks, expectedLinksPerListPage);
                int delta = (foundLinks - expectedLinksPerListPage) * expectedMatchesPerPost;
                if (delta != 0) tracker.addToTotal(Math.max(0, delta));
            } else {
                log.debug("[scrape] page {}: {} post links", page, foundLinks);
            }

            int linkIdx = 0;
            for (String postUrl : postUrls) {
                linkIdx++;
                String postId = externalIdFromUrl(postUrl);
                String ctx = String.format("page %d/%d | post %d/%d | id=%s", (page - fromPage + 1), pages, linkIdx, foundLinks, postId);
                scrapeSinglePostInternal(postUrl, postId, tracker, ctx);
                politePause();
            }
        }

        tracker.finish("ALL PAGES DONE");
        log.info("[scrape] completed page range {}..{}", fromPage, toPage);
    }

    public void scrapeSinglePost(String id) throws IOException {
        String postUrl = buildPostUrlFromId(id);
        ProgressTracker tracker = ProgressTracker.create(expectedMatchesPerPost);
        scrapeSinglePostInternal(postUrl, id, tracker, "single-post id=" + id);
        tracker.finish("SINGLE POST DONE");
    }

    private void scrapeSinglePostInternal(String postUrl, String postId, ProgressTracker tracker, String context)
            throws IOException {

        Document postDoc = fetch(postUrl);
        List<MatchRow> rows = parseMatchRows(postDoc);

        if (rows.size() != expectedMatchesPerPost) {
            log.warn("[scrape] post {}: found {} matches (expected ~{})", postId, rows.size(), expectedMatchesPerPost);
            int delta = rows.size() - expectedMatchesPerPost;
            if (delta > 0) tracker.addToTotal(delta);
        } else {
            log.debug("[scrape] post {}: {} matches", postId, rows.size());
        }

        int rowIdx = 0;
        for (MatchRow row : rows) {
            rowIdx++;
            boolean saved = upsertMatch(postId, row);
            String ctx = context + String.format(" | match %d/%d", rowIdx, rows.size());
            tracker.tick(saved, ctx);
        }
    }

    // ============================= persistence ==============================
    private boolean upsertMatch(String postId, MatchRow row) {
        String externalId = row.externalId;
        if (!StringUtils.hasText(externalId)) {
            externalId = postId + "-" + row.hashKey();
        }

        Optional<Match> existing = matchRepository.findByExternalId(externalId);
        if (existing.isPresent()) {
            log.debug("[scrape] skip {}, already in DB", externalId);
            return false;
        }

        Player p1 = findOrCreate(row.player1First, row.player1Last);
        Player p2 = findOrCreate(row.player2First, row.player2Last);

        Match m = new Match();
        m.setExternalId(externalId);
        // NOTE: your entity uses setDate(...), not setMatchDate(...)
        m.setDate(row.date);
        m.setPlayer1(p1);
        m.setPlayer2(p2);
        m.setResult(row.result);

        matchRepository.save(m);
        log.debug("[scrape] saved match {} -> {} {} vs {} {}", externalId, row.player1Last, row.player1First, row.player2Last, row.player2First);
        return true;
    }

    private Player findOrCreate(String first, String last) {
        return playerRepository
                .findByFirstNameIgnoreCaseAndLastNameIgnoreCase(first, last)
                .orElseGet(() -> {
                    Player p = new Player();
                    p.setFirstName(first);
                    p.setLastName(last);
                    return playerRepository.save(p);
                });
    }

    // ============================== HTML helpers ============================
    private Document fetch(String url) throws IOException {
        log.debug("[scrape] GET {}", url);
        try {
            return Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (compatible; TTLBot/1.0)")
                    .timeout((int) TimeUnit.SECONDS.toMillis(20))
                    .get();
        } catch (IOException e) {
            throw new IOException("fetch failed " + url + ": " + e.getMessage(), e);
        }
    }

    private String buildListPageUrl(int page) {
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        String lp = listPath.startsWith("/") ? listPath : ("/" + listPath);
        return normalized + lp + "/page/" + page + "/";
    }

    private String buildPostUrlFromId(String id) {
        String normalized = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return normalized + "/" + id + "-";
    }

    private String externalIdFromUrl(String url) {
        try {
            String s = url;
            if (s.endsWith("/")) s = s.substring(0, s.length() - 1);
            int slash = s.lastIndexOf('/');
            if (slash >= 0) s = s.substring(slash + 1);
            int dash = s.indexOf('-');
            if (dash > 0) s = s.substring(0, dash);
            return s;
        } catch (Exception e) {
            return url;
        }
    }

    private List<String> extractPostUrls(Document listDoc) {
        List<String> urls = new ArrayList<>();
        for (String selector : cssPostLinks.split(",")) {
            Elements links = listDoc.select(selector.trim());
            for (Element a : links) {
                String href = a.attr("abs:href");
                if (StringUtils.hasText(href)) urls.add(href);
            }
            if (!urls.isEmpty()) break;
        }
        return new ArrayList<>(new LinkedHashSet<>(urls));
    }

    private List<MatchRow> parseMatchRows(Document postDoc) {
        List<MatchRow> out = new ArrayList<>();
        Elements rows = postDoc.select(cssMatchRows);
        for (Element tr : rows) {
            Elements tds = tr.select("td");
            if (tds.size() == 0) continue;

            String p1 = text(tds, colPlayer1);
            String p2 = text(tds, colPlayer2);
            String result = text(tds, colResult);
            String dateStr = text(tds, colDate);

            String[] p1s = splitName(p1);
            String[] p2s = splitName(p2);

            MatchRow mr = new MatchRow();
            mr.player1First = p1s[0]; mr.player1Last = p1s[1];
            mr.player2First = p2s[0]; mr.player2Last = p2s[1];
            mr.result = result;
            mr.date = safeParseDate(dateStr);
            mr.externalId = extractRowExternalId(tr).orElse(null);
            out.add(mr);
        }
        return out;
    }

    private Optional<String> extractRowExternalId(Element tr) {
        Element a = tr.selectFirst("a[href]");
        if (a == null) return Optional.empty();
        String href = a.attr("abs:href");
        if (!StringUtils.hasText(href)) return Optional.empty();
        return Optional.of(externalIdFromUrl(href));
    }

    private static String text(Elements tds, int idx) {
        if (idx < 0 || idx >= tds.size()) return "";
        return tds.get(idx).text().trim();
    }

    private static String[] splitName(String full) {
        if (!StringUtils.hasText(full)) return new String[]{"", ""};
        String[] parts = full.trim().split("\\s+");
        if (parts.length == 1) return new String[]{"", parts[0]};
        String last = parts[parts.length - 1];
        String first = String.join(" ", Arrays.copyOf(parts, parts.length - 1));
        return new String[]{first, last};
    }

    private LocalDate safeParseDate(String s) {
        if (!StringUtils.hasText(s)) return LocalDate.now();
        try {
            if (s.matches("\\d{4}-\\d{2}-\\d{2}")) return LocalDate.parse(s, dateFmt);
            if (s.matches("\\d{2}\\.\\d{2}\\.\\d{4}")) {
                String[] p = s.split("\\.");
                return LocalDate.of(Integer.parseInt(p[2]), Integer.parseInt(p[1]), Integer.parseInt(p[0]));
            }
        } catch (Exception ignore) { }
        return LocalDate.now();
    }

    private void politePause() {
        if (delayBetweenRequestsMs <= 0) return;
        try { Thread.sleep(delayBetweenRequestsMs); } catch (InterruptedException ignored) { }
    }


    // Simple struct for a parsed match row
    private static class MatchRow {
        String player1First, player1Last;
        String player2First, player2Last;
        String result;
        LocalDate date;
        String externalId;

        String hashKey() {
            return (player1First + "|" + player1Last + "|" + player2First + "|" + player2Last + "|" + result + "|" + date)
                    .replaceAll("\\s+", "_");
        }
    }
}