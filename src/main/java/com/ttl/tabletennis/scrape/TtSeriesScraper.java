package com.ttl.tabletennis.scrape;

import com.ttl.tabletennis.domain.Match;
import com.ttl.tabletennis.domain.Player;
import com.ttl.tabletennis.domain.ScrapeError;
import com.ttl.tabletennis.domain.ScrapeRun;
import com.ttl.tabletennis.repository.MatchRepository;
import com.ttl.tabletennis.repository.ScrapeErrorRepository;
import com.ttl.tabletennis.repository.ScrapeRunRepository;
import com.ttl.tabletennis.service.PlayerIdentityService;
import com.ttl.tabletennis.util.MatchResultParser;
import com.ttl.tabletennis.util.CorrelationContext;
import com.ttl.tabletennis.util.NameUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class TtSeriesScraper {

    private static final Logger log = LoggerFactory.getLogger(TtSeriesScraper.class);

    private final MatchRepository matchRepository;
    private final PlayerIdentityService playerIdentityService;
    private final ScrapeRunRepository scrapeRunRepository;
    private final ScrapeErrorRepository scrapeErrorRepository;

    private final AtomicBoolean scrapeRunning = new AtomicBoolean(false);
    private final AtomicInteger lastSavedMatches = new AtomicInteger(0);
    private final AtomicReference<LocalDateTime> lastStartedAt = new AtomicReference<>();
    private final AtomicReference<LocalDateTime> lastFinishedAt = new AtomicReference<>();
    private final AtomicReference<String> lastMode = new AtomicReference<>("IDLE");
    private final AtomicReference<String> lastError = new AtomicReference<>();
    private final AtomicInteger runSequence = new AtomicInteger();
    private final AtomicReference<ActiveRun> activeRun = new AtomicReference<>();

    @Value("${ttl.baseUrl:https://www.tt-series.com}")
    private String baseUrl;

    @Value("${ttl.listPath:/category/turnieje}")
    private String listPath;

    @Value("${ttl.startPage:1}")
    private int startPage;

    @Value("${ttl.endPage:1}")
    private int endPage;

    @Value("${ttl.onlyId:}")
    private String onlyId;

    @Value("${ttl.linksPerPage:10}")
    private int expectedLinksPerListPage;

    @Value("${ttl.matchesPerPost:12}")
    private int expectedMatchesPerPost;

    @Value("${ttl.delayBetweenRequestsMs:350}")
    private long delayBetweenRequestsMs;

    @Value("${ttl.retry.maxAttempts:3}")
    private int retryMaxAttempts;

    @Value("${ttl.retry.initialBackoffMs:400}")
    private long retryInitialBackoffMs;

    @Value("${ttl.retry.maxBackoffMs:4000}")
    private long retryMaxBackoffMs;

    @Value("${ttl.errorHtmlSnippetLength:1200}")
    private int errorHtmlSnippetLength;

    @Value("${scrape.auto:true}")
    private boolean auto;

    @Value("${ttl.css.postLinks:.elementor-post__thumbnail__link, a.elementor-post__read-more, a.elementor-post__title__link, #content > .entry .entry-title > a, .masonry-post .entry-title > a}")
    private String cssPostLinks;

    @Value("${ttl.css.matchRows:table tbody tr}")
    private String cssMatchRows;

    @Value("${ttl.col.player1:0}")
    private int colPlayer1;

    @Value("${ttl.col.player2:1}")
    private int colPlayer2;

    @Value("${ttl.col.result:2}")
    private int colResult;

    @Value("${ttl.col.date:3}")
    private int colDate;

    @Value("${ttl.scrape.minYear:1990}")
    private int minScrapeYear;

    @Value("${ttl.scrape.maxFutureYears:1}")
    private int maxScrapeFutureYears;

    private final DateTimeFormatter dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public TtSeriesScraper(MatchRepository matchRepository,
                           PlayerIdentityService playerIdentityService,
                           ScrapeRunRepository scrapeRunRepository,
                           ScrapeErrorRepository scrapeErrorRepository) {
        this.matchRepository = matchRepository;
        this.playerIdentityService = playerIdentityService;
        this.scrapeRunRepository = scrapeRunRepository;
        this.scrapeErrorRepository = scrapeErrorRepository;
    }

    public void run() {
        runManual();
    }

    public void autoScrape() {
        runAuto();
    }

    public void scrapePost(int id) {
        executeRun("SINGLE_POST", () -> scrapeSinglePost(String.valueOf(id)));
    }

    public List<String> scrapeMatchLinks(int fromPage, int toPage) throws IOException, InterruptedException {
        List<String> all = new ArrayList<>();
        for (int page = fromPage; page <= toPage; page++) {
            String listUrl = buildListPageUrl(page);
            Document listDoc = fetch(listUrl, "collect-links page=" + page);
            all.addAll(extractPostUrls(listDoc));
            politePause();
        }
        return new ArrayList<>(new LinkedHashSet<>(all));
    }

    public int scrapeAndSaveMatchDetails(List<String> postUrls) throws IOException {
        if (postUrls == null || postUrls.isEmpty()) return 0;

        int savedTotal = 0;
        int i = 0;

        for (String postUrl : postUrls) {
            i++;
            String postId = externalIdFromUrl(postUrl);

            Document postDoc = fetch(postUrl, "bulk-post id=" + postId);
            List<MatchRow> rows = parseMatchRows(postDoc, postUrl, "bulk-post id=" + postId);

            int savedHere = 0;
            for (MatchRow row : rows) {
                if (upsertMatch(postId, row)) {
                    savedHere++;
                }
            }

            savedTotal += savedHere;
            log.info("[scrape] {}/{}: saved {} match(es) from {}", i, postUrls.size(), savedHere, postId);

            politePause();
        }

        log.info("[scrape] BULK URLS DONE - total saved: {}", savedTotal);
        return savedTotal;
    }

    public int refreshRecentOfficialResults(int pages) throws IOException, InterruptedException {
        int safePages = Math.max(1, pages);
        List<String> postUrls = scrapeMatchLinks(1, safePages);
        if (postUrls.isEmpty()) {
            log.info("[scrape] official-result refresh found no recent posts across {} page(s)", safePages);
            return 0;
        }
        return scrapeAndSaveMatchDetails(postUrls);
    }

    public List<OfficialLedgerMatch> lookupOfficialMatchesForPair(String player1Name,
                                                                  String player2Name,
                                                                  int limit) {
        if (!StringUtils.hasText(player1Name) || !StringUtils.hasText(player2Name)) {
            return List.of();
        }

        String leftQuery = toTtSeriesQueryName(player1Name);
        String rightQuery = toTtSeriesQueryName(player2Name);
        if (!StringUtils.hasText(leftQuery) || !StringUtils.hasText(rightQuery)) {
            return List.of();
        }

        int take = Math.max(1, Math.min(limit, 40));
        List<OfficialLedgerMatch> merged = new ArrayList<>();
        merged.addAll(fetchOfficialLedgerMatches(buildH2hUrl(leftQuery, rightQuery), "official-h2h"));
        merged.addAll(fetchOfficialLedgerMatches(buildPlayerUrl(leftQuery), "official-player-left"));
        merged.addAll(fetchOfficialLedgerMatches(buildPlayerUrl(rightQuery), "official-player-right"));
        if (merged.isEmpty()) {
            return List.of();
        }

        String leftLookup = NameUtils.normalizeForLookup(player1Name);
        String rightLookup = NameUtils.normalizeForLookup(player2Name);

        return merged.stream()
                .filter(match -> isSamePairLookup(
                        NameUtils.normalizeForLookup(match.player1Raw()),
                        NameUtils.normalizeForLookup(match.player2Raw()),
                        leftLookup,
                        rightLookup
                ))
                .sorted(Comparator
                        .comparing(OfficialLedgerMatch::date, Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing((OfficialLedgerMatch match) -> sourcePriority(match.sourceType()))
                        .thenComparing(OfficialLedgerMatch::sourceUrl))
                .distinct()
                .limit(take)
                .toList();
    }

    @Async("ttlScraperExecutor")
    public void runAuto() {
        if (!auto) {
            log.info("[scrape] auto=false -> idle");
            return;
        }
        executeRun("AUTO", () -> {
            if (StringUtils.hasText(onlyId)) {
                scrapeSinglePost(onlyId);
            } else {
                scrapePageRange(startPage, endPage);
            }
        });
    }

    public void runManual() {
        executeRun("MANUAL", () -> {
            if (StringUtils.hasText(onlyId)) {
                scrapeSinglePost(onlyId);
            } else {
                scrapePageRange(startPage, endPage);
            }
        });
    }

    public void runPageRange(int fromPage, int toPage) {
        int normalizedFrom = Math.max(1, fromPage);
        int normalizedTo = Math.max(normalizedFrom, toPage);
        executeRun("PAGE_RANGE", () -> scrapePageRange(normalizedFrom, normalizedTo));
    }

    public void scrapePageRange(int fromPage, int toPage) throws IOException, InterruptedException {
        final int pages = Math.max(0, toPage - fromPage + 1);
        int staticExpected = pages * expectedLinksPerListPage * expectedMatchesPerPost;
        ProgressTracker tracker = ProgressTracker.create(staticExpected);

        log.info("[scrape] begin pages {}..{} ({} pages). Expected ~= {} matches", fromPage, toPage, pages, staticExpected);

        for (int page = fromPage; page <= toPage; page++) {
            String listUrl = buildListPageUrl(page);
            Document listDoc = fetch(listUrl, "page-range page=" + page);

            List<String> postUrls = extractPostUrls(listDoc);
            int foundLinks = postUrls.size();

            if (foundLinks != expectedLinksPerListPage) {
                log.warn("[scrape] page {}: found {} links (expected ~{})", page, foundLinks, expectedLinksPerListPage);
                int delta = (foundLinks - expectedLinksPerListPage) * expectedMatchesPerPost;
                if (delta != 0) tracker.addToTotal(Math.max(0, delta));
            }
            if (foundLinks == 0) {
                addErrorRecord(new ScrapeErrorRecord(currentRunId(), LocalDateTime.now(), currentMode(),
                        "No post links found for list page", listUrl,
                        "page-range page=" + page, htmlSnippet(listDoc)));
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

        Document postDoc = fetch(postUrl, context);
        List<MatchRow> rows = parseMatchRows(postDoc, postUrl, context);

        if (rows.size() != expectedMatchesPerPost) {
            log.warn("[scrape] post {}: found {} matches (expected ~{})", postId, rows.size(), expectedMatchesPerPost);
            int delta = rows.size() - expectedMatchesPerPost;
            if (delta > 0) tracker.addToTotal(delta);
        }
        if (rows.isEmpty()) {
            addErrorRecord(new ScrapeErrorRecord(currentRunId(), LocalDateTime.now(), currentMode(),
                    "No match rows parsed from post", postUrl, context, htmlSnippet(postDoc)));
        }

        int rowIdx = 0;
        for (MatchRow row : rows) {
            rowIdx++;
            boolean saved = upsertMatch(postId, row);
            String ctx = context + String.format(" | match %d/%d", rowIdx, rows.size());
            tracker.tick(saved, ctx);
        }
    }

    private boolean upsertMatch(String postId, MatchRow row) {
        String externalId = buildExternalId(postId, row);
        String sanitizedResult = sanitizeResultForPersistence(row.result, postId, externalId);

        Player p1 = playerIdentityService.resolveOrCreatePlayer(row.player1Raw);
        Player p2 = playerIdentityService.resolveOrCreatePlayer(row.player2Raw);

        Optional<Match> existing = matchRepository.findByExternalId(externalId);
        if (existing.isPresent()) {
            Match current = existing.get();
            boolean changed = false;

            if (!samePlayer(current.getPlayer1(), p1)) {
                current.setPlayer1(p1);
                changed = true;
            }
            if (!samePlayer(current.getPlayer2(), p2)) {
                current.setPlayer2(p2);
                changed = true;
            }
            if (!row.date.equals(current.getDate())) {
                current.setDate(row.date);
                changed = true;
            }

            if (sanitizedResult != null) {
                String existingResult = current.getResult() == null ? "" : current.getResult().trim();
                String incomingResult = sanitizedResult.trim();
                if (!incomingResult.equals(existingResult)) {
                    MatchResultParser.applyToMatch(current, incomingResult);
                    changed = true;
                }
            }

            if (changed) {
                matchRepository.save(current);
                log.debug("[scrape] updated match {}", externalId);
            } else {
                log.debug("[scrape] skip {}, already in DB", externalId);
            }
            return false;
        }

        Match match = new Match();
        match.setExternalId(externalId);
        match.setDate(row.date);
        match.setPlayer1(p1);
        match.setPlayer2(p2);
        MatchResultParser.applyToMatch(match, sanitizedResult);

        matchRepository.save(match);
        lastSavedMatches.incrementAndGet();
        ActiveRun run = activeRun.get();
        if (run != null) {
            run.savedMatches.incrementAndGet();
        }
        log.debug("[scrape] saved match {} -> {} vs {}", externalId, row.player1Raw, row.player2Raw);
        return true;
    }

    private String sanitizeResultForPersistence(String rawResult, String postId, String externalId) {
        if (rawResult == null) {
            return null;
        }
        String trimmed = rawResult.trim();
        if (trimmed.isBlank()) {
            return "";
        }
        if (MatchResultParser.isAcceptedResultFormat(trimmed)) {
            return trimmed;
        }

        String digitsOnly = trimmed.replaceAll("[^0-9]", "");
        if (digitsOnly.length() == 2) {
            String candidate = digitsOnly.charAt(0) + ":" + digitsOnly.charAt(1);
            if (MatchResultParser.isAcceptedResultFormat(candidate)) {
                log.warn("[scrape] normalized compact result '{}' -> '{}' for {}", trimmed, candidate, externalId);
                return candidate;
            }
        }

        String context = "post=" + postId + " externalId=" + externalId;
        String message = "Invalid scraped result format '" + trimmed + "'; storing without result";
        addErrorRecord(new ScrapeErrorRecord(
                currentRunId(),
                LocalDateTime.now(),
                currentMode(),
                message,
                null,
                context,
                null
        ));
        log.warn("[scrape] {} [{}]", message, context);
        return null;
    }

    private String buildExternalId(String postId, MatchRow row) {
        String externalId = row.externalId;
        if (!StringUtils.hasText(externalId)) {
            externalId = postId + "-" + row.hashKey();
        }
        if (externalId.length() <= 64) {
            return externalId;
        }

        String compactHash = java.util.UUID
                .nameUUIDFromBytes(externalId.getBytes(StandardCharsets.UTF_8))
                .toString()
                .replace("-", "");

        String prefix = StringUtils.hasText(postId) ? postId.replaceAll("[^a-zA-Z0-9]", "") : "post";
        if (!StringUtils.hasText(prefix)) {
            prefix = "post";
        }
        int maxPrefix = Math.max(1, 63 - compactHash.length());
        if (prefix.length() > maxPrefix) {
            prefix = prefix.substring(0, maxPrefix);
        }
        return prefix + "-" + compactHash;
    }

    private static boolean samePlayer(Player current, Player next) {
        if (current == null || next == null) return false;
        return current.getId() != null && current.getId().equals(next.getId());
    }

    private Document fetch(String url) throws IOException {
        return fetch(url, "http-fetch");
    }

    private Document fetch(String url, String context) throws IOException {
        log.debug("[scrape] GET {}", url);
        int attempts = Math.max(1, retryMaxAttempts);
        long backoff = Math.max(0L, retryInitialBackoffMs);
        long maxBackoff = retryMaxBackoffMs <= 0 ? Math.max(backoff, 250L) : retryMaxBackoffMs;
        IOException last = null;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                return Jsoup.connect(url)
                        .userAgent("Mozilla/5.0 (compatible; TTLBot/1.0)")
                        .timeout((int) TimeUnit.SECONDS.toMillis(20))
                        .get();
            } catch (IOException e) {
                last = e;
                if (attempt >= attempts) {
                    throw new ScrapeOperationException("fetch failed " + url + ": " + e.getMessage(), e, url, context, null);
                }

                long sleepFor = Math.min(Math.max(250L, backoff), maxBackoff);
                String message = String.format("fetch attempt %d/%d failed: %s (retrying in %dms)",
                        attempt, attempts, e.getMessage(), sleepFor);
                addErrorRecord(new ScrapeErrorRecord(currentRunId(), LocalDateTime.now(), currentMode(),
                        message, url, context, null));
                sleepQuietly(sleepFor);
                backoff = Math.min(maxBackoff, Math.max(250L, backoff * 2L));
            }
        }

        throw new ScrapeOperationException("fetch failed " + url + ": " + (last == null ? "unknown" : last.getMessage()),
                last, url, context, null);
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

    private List<MatchRow> parseMatchRows(Document postDoc, String pageUrl, String context) throws IOException {
        try {
            List<MatchRow> out = new ArrayList<>();
            LocalDate fallbackDate = extractPostDate(postDoc).orElse(LocalDate.now());
            Elements rows = postDoc.select(cssMatchRows);
            int rowOrdinal = 0;
            for (Element tr : rows) {
                rowOrdinal++;
                Elements tds = tr.select("td");
                if (tds.isEmpty()) continue;

                ParsedColumns parsedColumns = resolveColumns(tds);
                String p1 = text(tds, parsedColumns.player1Col());
                String p2 = text(tds, parsedColumns.player2Col());
                String result = text(tds, parsedColumns.resultCol());
                String dateStr = parsedColumns.dateCol() >= 0 ? text(tds, parsedColumns.dateCol()) : "";
                if (!isLikelyPlayerCell(p1) || !isLikelyPlayerCell(p2)) continue;

                MatchRow row = new MatchRow();
                row.player1Raw = p1;
                row.player2Raw = p2;
                row.result = result;
                row.date = safeParseDate(dateStr, fallbackDate);
                row.externalId = extractRowExternalId(tr).orElse(null);
                row.rowOrdinal = rowOrdinal;
                if (parsedColumns.slotColA() >= 0 || parsedColumns.slotColB() >= 0) {
                    row.slotKey = (text(tds, parsedColumns.slotColA()) + "|" + text(tds, parsedColumns.slotColB())).trim();
                }
                out.add(row);
            }
            return out;
        } catch (Exception e) {
            throw new ScrapeOperationException("parse failed: " + e.getMessage(), e, pageUrl, context, htmlSnippet(postDoc));
        }
    }

    private ParsedColumns resolveColumns(Elements tds) {
        if (tds.size() >= 5) {
            String modernP1 = text(tds, 2);
            String modernP2 = text(tds, 3);
            if (isLikelyPlayerCell(modernP1) && isLikelyPlayerCell(modernP2)) {
                return new ParsedColumns(2, 3, 4, -1, 0, 1);
            }
        }
        return new ParsedColumns(colPlayer1, colPlayer2, colResult, colDate, -1, -1);
    }

    private boolean isLikelyPlayerCell(String value) {
        if (!StringUtils.hasText(value)) return false;
        String normalized = value.trim().toLowerCase();
        if (!normalized.matches(".*\\p{L}.*")) return false;
        return !isHeaderCell(normalized);
    }

    private boolean isHeaderCell(String normalizedLowercase) {
        String s = normalizedLowercase == null ? "" : normalizedLowercase;
        return s.equals("player")
                || s.equals("name")
                || s.equals("number")
                || s.equals("result")
                || s.equals("matches")
                || s.equals("sets")
                || s.equals("ranking")
                || s.contains("match order")
                || s.contains("hour");
    }

    private Optional<LocalDate> extractPostDate(Document postDoc) {
        Optional<LocalDate> fromTitle = extractDateFromText(postDoc.title());
        if (fromTitle.isPresent()) {
            return fromTitle;
        }

        Element canonical = postDoc.selectFirst("link[rel=canonical]");
        if (canonical != null) {
            Optional<LocalDate> fromCanonical = extractDateFromText(canonical.attr("href"));
            if (fromCanonical.isPresent()) {
                return fromCanonical;
            }
        }
        return Optional.empty();
    }

    private Optional<LocalDate> extractDateFromText(String value) {
        if (!StringUtils.hasText(value)) return Optional.empty();

        java.util.regex.Matcher dotted = java.util.regex.Pattern
                .compile("(\\d{2})\\.(\\d{2})\\.(\\d{4})")
                .matcher(value);
        if (dotted.find()) {
            LocalDate parsed = LocalDate.of(
                    Integer.parseInt(dotted.group(3)),
                    Integer.parseInt(dotted.group(2)),
                    Integer.parseInt(dotted.group(1))
            );
            return isReasonableDate(parsed) ? Optional.of(parsed) : Optional.empty();
        }

        java.util.regex.Matcher dashed = java.util.regex.Pattern
                .compile("(\\d{2})-(\\d{2})-(\\d{4})")
                .matcher(value);
        if (dashed.find()) {
            LocalDate parsed = LocalDate.of(
                    Integer.parseInt(dashed.group(3)),
                    Integer.parseInt(dashed.group(2)),
                    Integer.parseInt(dashed.group(1))
            );
            return isReasonableDate(parsed) ? Optional.of(parsed) : Optional.empty();
        }
        return Optional.empty();
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

    private LocalDate safeParseDate(String s) {
        return safeParseDate(s, LocalDate.now());
    }

    private LocalDate safeParseDate(String s, LocalDate fallback) {
        if (!StringUtils.hasText(s)) return fallback;
        try {
            if (s.matches("\\d{4}-\\d{2}-\\d{2}")) {
                LocalDate parsed = LocalDate.parse(s, dateFmt);
                return isReasonableDate(parsed) ? parsed : fallback;
            }
            if (s.matches("\\d{2}\\.\\d{2}\\.\\d{4}")) {
                String[] p = s.split("\\.");
                LocalDate parsed = LocalDate.of(Integer.parseInt(p[2]), Integer.parseInt(p[1]), Integer.parseInt(p[0]));
                return isReasonableDate(parsed) ? parsed : fallback;
            }
        } catch (Exception ignore) {
        }
        return fallback;
    }

    private boolean isReasonableDate(LocalDate candidate) {
        if (candidate == null) {
            return false;
        }
        int minYear = Math.max(1900, Math.min(2100, minScrapeYear));
        int futureYears = Math.max(0, Math.min(5, maxScrapeFutureYears));
        LocalDate minDate = LocalDate.of(minYear, 1, 1);
        LocalDate maxDate = LocalDate.now().plusYears(futureYears);
        return !(candidate.isBefore(minDate) || candidate.isAfter(maxDate));
    }

    private List<OfficialLedgerMatch> fetchOfficialLedgerMatches(String url, String context) {
        if (!StringUtils.hasText(url)) {
            return List.of();
        }
        try {
            Document doc = fetch(url, context);
            return parseOfficialLedgerRows(doc, url, context);
        } catch (Exception ex) {
            log.warn("[scrape] official ledger fetch failed for {}: {}", url, ex.getMessage());
            return List.of();
        }
    }

    private List<OfficialLedgerMatch> parseOfficialLedgerRows(Document doc, String url, String context) {
        if (doc == null) {
            return List.of();
        }
        List<OfficialLedgerMatch> out = new ArrayList<>();
        for (Element table : doc.select("table")) {
            Optional<OfficialLedgerColumns> resolvedColumns = resolveOfficialLedgerColumns(table);
            if (resolvedColumns.isEmpty()) {
                continue;
            }
            OfficialLedgerColumns columns = resolvedColumns.get();
            Elements rows = table.select("tr");
            for (int rowIndex = columns.firstDataRowIndex(); rowIndex < rows.size(); rowIndex++) {
                Element tr = rows.get(rowIndex);
                Elements cells = tr.select("td, th");
                if (cells.isEmpty() || cells.size() <= columns.maxIndex()) {
                    continue;
                }
                String player1Raw = text(cells, columns.player1Col());
                String resultRaw = text(cells, columns.resultCol());
                String player2Raw = text(cells, columns.player2Col());
                String dateRaw = text(cells, columns.dateCol());
                String winnerRaw = text(cells, columns.winnerCol());
                if (!isLikelyPlayerCell(player1Raw) || !isLikelyPlayerCell(player2Raw)) {
                    continue;
                }
                LocalDate parsedDate = safeParseDate(dateRaw, null);
                if (!isReasonableDate(parsedDate)) {
                    continue;
                }
                String normalizedResult = normalizeLedgerResult(resultRaw);
                out.add(new OfficialLedgerMatch(
                        context,
                        url,
                        player1Raw.trim(),
                        player2Raw.trim(),
                        normalizedResult,
                        parsedDate,
                        StringUtils.hasText(winnerRaw) ? winnerRaw.trim() : null
                ));
            }
        }
        return out;
    }

    private Optional<OfficialLedgerColumns> resolveOfficialLedgerColumns(Element table) {
        if (table == null) {
            return Optional.empty();
        }
        Elements rows = table.select("tr");
        for (int rowIndex = 0; rowIndex < Math.min(rows.size(), 6); rowIndex++) {
            Element row = rows.get(rowIndex);
            Elements cells = row.select("th, td");
            if (cells.size() < 5) {
                continue;
            }
            int player1Col = -1;
            int resultCol = -1;
            int player2Col = -1;
            int dateCol = -1;
            int winnerCol = -1;
            for (int col = 0; col < cells.size(); col++) {
                String header = normalizeLedgerHeader(cells.get(col).text());
                if (header.startsWith("player1")) {
                    player1Col = col;
                } else if (header.equals("score") || header.equals("result")) {
                    resultCol = col;
                } else if (header.startsWith("player2")) {
                    player2Col = col;
                } else if (header.equals("date")) {
                    dateCol = col;
                } else if (header.equals("winner")) {
                    winnerCol = col;
                }
            }
            if (player1Col >= 0 && resultCol >= 0 && player2Col >= 0 && dateCol >= 0 && winnerCol >= 0) {
                return Optional.of(new OfficialLedgerColumns(player1Col, resultCol, player2Col, dateCol, winnerCol, rowIndex + 1));
            }
        }
        return Optional.empty();
    }

    private String normalizeLedgerHeader(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        return raw.trim()
                .toLowerCase(Locale.ROOT)
                .replace('\u00a0', ' ')
                .replaceAll("[^a-z0-9]+", "");
    }

    private String normalizeLedgerResult(String rawResult) {
        if (!StringUtils.hasText(rawResult)) {
            return "";
        }
        String trimmed = rawResult.trim();
        if (MatchResultParser.isAcceptedResultFormat(trimmed)) {
            return trimmed.replace('/', ':');
        }
        return "";
    }

    private String toTtSeriesQueryName(String rawName) {
        if (!StringUtils.hasText(rawName)) {
            return "";
        }
        String[] split = NameUtils.splitFirstLast(rawName);
        String first = split[0] == null ? "" : NameUtils.cleanRawName(split[0]);
        String last = split[1] == null ? "" : NameUtils.cleanRawName(split[1]);
        String combined = (last + " " + first).trim();
        if (combined.isBlank()) {
            return NameUtils.cleanRawName(rawName);
        }
        return combined;
    }

    private String buildPlayerUrl(String queryName) {
        return normalizedBaseUrl() + "/player/?player=" + encodeQueryValue(queryName);
    }

    private String buildH2hUrl(String playerAQueryName, String playerBQueryName) {
        return normalizedBaseUrl() + "/h2h/?player_a=" + encodeQueryValue(playerAQueryName)
                + "&player_b=" + encodeQueryValue(playerBQueryName);
    }

    private String normalizedBaseUrl() {
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    private String encodeQueryValue(String value) {
        return URLEncoder.encode(value == null ? "" : value.trim(), StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    private boolean isSamePairLookup(String leftA, String rightA, String leftB, String rightB) {
        if (!StringUtils.hasText(leftA) || !StringUtils.hasText(rightA)
                || !StringUtils.hasText(leftB) || !StringUtils.hasText(rightB)) {
            return false;
        }
        return (leftA.equals(leftB) && rightA.equals(rightB))
                || (leftA.equals(rightB) && rightA.equals(leftB));
    }

    private int sourcePriority(String sourceType) {
        if (!StringUtils.hasText(sourceType)) {
            return 10;
        }
        String normalized = sourceType.trim().toLowerCase(Locale.ROOT);
        if (normalized.contains("h2h")) {
            return 0;
        }
        if (normalized.contains("player")) {
            return 1;
        }
        return 5;
    }

    private void politePause() {
        if (delayBetweenRequestsMs <= 0) return;
        try {
            Thread.sleep(delayBetweenRequestsMs);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private record ParsedColumns(int player1Col,
                                 int player2Col,
                                 int resultCol,
                                 int dateCol,
                                 int slotColA,
                                 int slotColB) {
    }

    private static class MatchRow {
        String player1Raw;
        String player2Raw;
        String result;
        LocalDate date;
        String externalId;
        String slotKey;
        int rowOrdinal;

        String hashKey() {
            String normalizedP1 = NameUtils.normalizeForLookup(player1Raw);
            String normalizedP2 = NameUtils.normalizeForLookup(player2Raw);
            String stableSlot = slotKey == null ? "" : slotKey.trim().toLowerCase();
            if (stableSlot.isBlank()) {
                stableSlot = "row-" + rowOrdinal;
            }
            return (normalizedP1 + "|" + normalizedP2 + "|" + stableSlot + "|" + date)
                    .replaceAll("\\s+", "_")
                    .replaceAll("[^a-zA-Z0-9_\\-|]", "");
        }
    }

    private record OfficialLedgerColumns(int player1Col,
                                         int resultCol,
                                         int player2Col,
                                         int dateCol,
                                         int winnerCol,
                                         int firstDataRowIndex) {
        int maxIndex() {
            return Math.max(Math.max(player1Col, resultCol), Math.max(Math.max(player2Col, dateCol), winnerCol));
        }
    }

    public record OfficialLedgerMatch(String sourceType,
                                      String sourceUrl,
                                      String player1Raw,
                                      String player2Raw,
                                      String result,
                                      LocalDate date,
                                      String winnerRaw) {
    }

    public ScrapeStatus status() {
        return new ScrapeStatus(
                scrapeRunning.get(),
                lastMode.get(),
                lastStartedAt.get(),
                lastFinishedAt.get(),
                lastSavedMatches.get(),
                lastError.get()
        );
    }

    public List<ScrapeRunRecord> recentRuns(String status, String mode, Integer limit) {
        String statusFilter = status == null ? null : status.trim().toUpperCase();
        String modeFilter = mode == null ? null : mode.trim().toUpperCase();
        int take = Math.max(1, Math.min(limit == null ? 25 : limit, 200));
        return scrapeRunRepository.findRecent(statusFilter, modeFilter, PageRequest.of(0, take))
                .stream()
                .map(run -> new ScrapeRunRecord(
                        run.getRunNumber() == null ? -1 : run.getRunNumber(),
                        run.getMode(),
                        run.getStartedAt(),
                        run.getFinishedAt(),
                        run.getStatus(),
                        run.getMatchesAdded(),
                        run.getErrorMessage()
                ))
                .toList();
    }

    public List<ScrapeErrorRecord> recentErrors(Integer limit) {
        int take = Math.max(1, Math.min(limit == null ? 25 : limit, 200));
        return scrapeErrorRepository.findRecent(null, PageRequest.of(0, take))
                .stream()
                .map(error -> new ScrapeErrorRecord(
                        error.getRunNumber() == null ? -1 : error.getRunNumber(),
                        error.getOccurredAt(),
                        error.getMode(),
                        error.getMessage(),
                        error.getUrl(),
                        error.getContext(),
                        error.getHtmlSnippet()
                ))
                .toList();
    }

    public ScrapeMetrics metrics(Integer limit) {
        int take = Math.max(1, Math.min(limit == null ? 200 : limit, 1000));
        List<ScrapeRun> runs = scrapeRunRepository.findRecent(null, null, PageRequest.of(0, take));
        if (runs.isEmpty()) {
            return new ScrapeMetrics(0, 0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, null);
        }

        int totalRuns = runs.size();
        int successRuns = 0;
        int failedRuns = 0;
        long matchesTotal = 0L;
        LocalDateTime lastRunAt = null;
        List<Long> durations = new ArrayList<>();

        for (ScrapeRun run : runs) {
            String status = run.getStatus() == null ? "" : run.getStatus().trim().toUpperCase();
            if ("SUCCESS".equals(status)) successRuns++;
            if ("FAILED".equals(status)) failedRuns++;

            matchesTotal += Math.max(0, run.getMatchesAdded());

            LocalDateTime started = run.getStartedAt();
            LocalDateTime finished = run.getFinishedAt();
            if (started != null && finished != null && !finished.isBefore(started)) {
                long seconds = Duration.between(started, finished).getSeconds();
                durations.add(Math.max(0L, seconds));
                if (lastRunAt == null || finished.isAfter(lastRunAt)) {
                    lastRunAt = finished;
                }
            } else if (started != null && (lastRunAt == null || started.isAfter(lastRunAt))) {
                lastRunAt = started;
            }
        }

        durations.sort(Long::compareTo);
        double avgDuration = durations.stream().mapToLong(Long::longValue).average().orElse(0.0);
        double medianDuration = durations.isEmpty() ? 0.0 : percentile(durations, 0.50);
        double p95Duration = durations.isEmpty() ? 0.0 : percentile(durations, 0.95);
        double successRate = totalRuns == 0 ? 0.0 : (successRuns / (double) totalRuns);
        double avgMatches = totalRuns == 0 ? 0.0 : (matchesTotal / (double) totalRuns);

        return new ScrapeMetrics(
                totalRuns,
                successRuns,
                failedRuns,
                successRate,
                avgDuration,
                medianDuration,
                p95Duration,
                avgMatches,
                lastRunAt
        );
    }

    private static double percentile(List<Long> sortedValues, double quantile) {
        if (sortedValues == null || sortedValues.isEmpty()) return 0.0;
        double clamped = Math.max(0.0, Math.min(1.0, quantile));
        int idx = (int) Math.ceil(clamped * sortedValues.size()) - 1;
        idx = Math.max(0, Math.min(sortedValues.size() - 1, idx));
        return sortedValues.get(idx);
    }

    public DryRunPreview dryRunListPage(int page) throws IOException {
        int safePage = Math.max(1, page);
        validateScraperConfig();
        String listUrl = buildListPageUrl(safePage);
        Document listDoc = fetch(listUrl, "dry-run page=" + safePage);
        List<String> postUrls = extractPostUrls(listDoc);
        List<String> sample = postUrls.stream().limit(10).toList();

        return new DryRunPreview(listUrl, safePage, cssPostLinks, postUrls.size(), sample);
    }

    private boolean markStart(String mode) {
        if (!scrapeRunning.compareAndSet(false, true)) {
            return false;
        }
        LocalDateTime now = LocalDateTime.now();
        int dbNext = scrapeRunRepository.findMaxRunNumber() + 1;
        int runId = runSequence.updateAndGet(current -> Math.max(current + 1, dbNext));
        ScrapeRun persisted = new ScrapeRun();
        persisted.setRunNumber(runId);
        persisted.setMode(mode);
        persisted.setStatus("RUNNING");
        persisted.setStartedAt(now);
        persisted.setSource((baseUrl == null ? "" : baseUrl) + (listPath == null ? "" : listPath));
        persisted = scrapeRunRepository.save(persisted);

        activeRun.set(new ActiveRun(runId, mode, now, persisted.getId()));
        lastMode.set(mode);
        lastStartedAt.set(now);
        lastSavedMatches.set(0);
        lastError.set(null);
        return true;
    }

    private void executeRun(String mode, CheckedRunnable runnable) {
        try (CorrelationContext.Scope ignored = CorrelationContext.openIfAbsent(null)) {
            if (!markStart(mode)) {
                String message = "Scrape already running. Wait for the active run to finish.";
                lastError.set(message);
                ActiveRun run = activeRun.get();
                int runId = run == null ? -1 : run.runId;
                addErrorRecord(new ScrapeErrorRecord(runId, LocalDateTime.now(), mode, message, null, "executeRun", null));
                log.warn("[scrape] {}", message);
                return;
            }
            try {
                validateScraperConfig();
                runnable.run();
            } catch (Exception e) {
                markError(e);
                log.warn("[scrape] FAILED: {}", e.getMessage(), e);
            } finally {
                markFinish();
            }
        }
    }

    private void validateScraperConfig() {
        if (!StringUtils.hasText(baseUrl)) {
            throw new IllegalStateException("Scraper configuration missing: ttl.baseUrl is empty.");
        }

        String normalizedBase = baseUrl.trim().toLowerCase();
        if (normalizedBase.contains("your-real-host")) {
            throw new IllegalStateException(
                    "Scraper configuration invalid: ttl.baseUrl still uses placeholder 'YOUR-REAL-HOST'. " +
                    "Set ttl.baseUrl in application.properties or environment variable TTL_BASE_URL."
            );
        }
        if (!(normalizedBase.startsWith("http://") || normalizedBase.startsWith("https://"))) {
            throw new IllegalStateException("Scraper configuration invalid: ttl.baseUrl must start with http:// or https://");
        }

        if (!StringUtils.hasText(listPath)) {
            throw new IllegalStateException("Scraper configuration missing: ttl.listPath is empty.");
        }
    }

    private void markError(Exception e) {
        String message = e.getMessage();
        if (message == null || message.isBlank()) {
            message = e.getClass().getSimpleName();
        }

        String url = null;
        String context = null;
        String htmlSnippet = null;
        if (e instanceof ScrapeOperationException scrapeOperationException) {
            url = scrapeOperationException.url;
            context = scrapeOperationException.context;
            htmlSnippet = scrapeOperationException.htmlSnippet;
        }

        lastError.set(message);
        ActiveRun run = activeRun.get();
        if (run != null) {
            run.errorMessage = message;
            run.status = "FAILED";
            addErrorRecord(new ScrapeErrorRecord(run.runId, LocalDateTime.now(), run.mode, message, url, context, htmlSnippet));
        } else {
            addErrorRecord(new ScrapeErrorRecord(-1, LocalDateTime.now(), lastMode.get(), message, url, context, htmlSnippet));
        }
    }

    private void markFinish() {
        if (!scrapeRunning.compareAndSet(true, false)) {
            return;
        }

        LocalDateTime finishedAt = LocalDateTime.now();
        lastFinishedAt.set(finishedAt);

        ActiveRun run = activeRun.getAndSet(null);
        if (run == null) return;

        String status = run.status;
        if (run.errorMessage != null && !"FAILED".equalsIgnoreCase(status)) {
            status = "FAILED";
        }
        if (run.errorMessage == null && "RUNNING".equalsIgnoreCase(status)) {
            status = "SUCCESS";
        }
        ScrapeRunRecord record = new ScrapeRunRecord(
                run.runId,
                run.mode,
                run.startedAt,
                finishedAt,
                status,
                run.savedMatches.get(),
                run.errorMessage
        );
        if (run.persistedRunId != null) {
            scrapeRunRepository.findById(run.persistedRunId).ifPresent(persisted -> {
                persisted.setFinishedAt(record.finishedAt());
                persisted.setStatus(record.status());
                persisted.setMatchesAdded(record.savedMatches());
                persisted.setErrorMessage(record.error());
                scrapeRunRepository.save(persisted);
            });
        }
    }

    private void addErrorRecord(ScrapeErrorRecord record) {
        ActiveRun run = activeRun.get();
        ScrapeRun persistedRun = null;
        if (run != null && run.persistedRunId != null) {
            persistedRun = scrapeRunRepository.findById(run.persistedRunId).orElse(null);
        }

        ScrapeError persisted = new ScrapeError();
        persisted.setScrapeRun(persistedRun);
        persisted.setRunNumber(record.runId());
        persisted.setOccurredAt(record.occurredAt());
        persisted.setMode(record.mode());
        persisted.setMessage(record.message());
        persisted.setUrl(record.url());
        persisted.setContext(record.context());
        persisted.setHtmlSnippet(record.htmlSnippet());
        scrapeErrorRepository.save(persisted);
    }

    private int currentRunId() {
        ActiveRun run = activeRun.get();
        return run == null ? -1 : run.runId;
    }

    private String currentMode() {
        ActiveRun run = activeRun.get();
        return run == null ? lastMode.get() : run.mode;
    }

    private String htmlSnippet(Document doc) {
        if (doc == null) return null;
        String html = doc.body() != null ? doc.body().html() : doc.html();
        if (html == null) return null;
        String normalized = html.replaceAll("\\s+", " ").trim();
        int maxLen = Math.max(100, errorHtmlSnippetLength);
        if (normalized.length() <= maxLen) {
            return normalized;
        }
        return normalized.substring(0, maxLen);
    }

    private void sleepQuietly(long millis) {
        if (millis <= 0) return;
        try {
            Thread.sleep(millis);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    public record ScrapeStatus(boolean running,
                               String mode,
                               LocalDateTime startedAt,
                               LocalDateTime finishedAt,
                               int savedMatches,
                               String error) {
    }

    public record ScrapeRunRecord(int runId,
                                  String mode,
                                  LocalDateTime startedAt,
                                  LocalDateTime finishedAt,
                                  String status,
                                  int savedMatches,
                                  String error) {
    }

    public record ScrapeErrorRecord(int runId,
                                    LocalDateTime occurredAt,
                                    String mode,
                                    String message,
                                    String url,
                                    String context,
                                    String htmlSnippet) {
    }

    public record ScrapeMetrics(int totalRuns,
                                int successRuns,
                                int failedRuns,
                                double successRate,
                                double averageDurationSeconds,
                                double medianDurationSeconds,
                                double p95DurationSeconds,
                                double averageMatchesAdded,
                                LocalDateTime lastRunAt) {
    }

    public record DryRunPreview(String listUrl,
                                int page,
                                String selector,
                                int postLinksFound,
                                List<String> sampleLinks) {
    }

    @FunctionalInterface
    private interface CheckedRunnable {
        void run() throws Exception;
    }

    private static final class ActiveRun {
        private final int runId;
        private final String mode;
        private final LocalDateTime startedAt;
        private final Long persistedRunId;
        private final AtomicInteger savedMatches = new AtomicInteger(0);
        private volatile String status = "RUNNING";
        private volatile String errorMessage;

        private ActiveRun(int runId, String mode, LocalDateTime startedAt, Long persistedRunId) {
            this.runId = runId;
            this.mode = mode;
            this.startedAt = startedAt;
            this.persistedRunId = persistedRunId;
        }
    }

    private static final class ScrapeOperationException extends IOException {
        private final String url;
        private final String context;
        private final String htmlSnippet;

        private ScrapeOperationException(String message, Throwable cause, String url, String context, String htmlSnippet) {
            super(message, cause);
            this.url = url;
            this.context = context;
            this.htmlSnippet = htmlSnippet;
        }
    }
}
