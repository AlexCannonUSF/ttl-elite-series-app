package com.ttl.tabletennis.scrape;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

final class TtSeriesPageParser {

    static List<String> extractPostUrls(Document listDoc, String cssPostLinks) {
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

    static Optional<String> rowExternalId(Element tr, java.util.function.Function<String,String> idFromUrl) {
        Element a = tr.selectFirst("a[href]");
        if (a == null) return Optional.empty();
        String href = a.attr("abs:href");
        if (!StringUtils.hasText(href)) return Optional.empty();
        return Optional.ofNullable(idFromUrl.apply(href));
    }

    static LocalDate coalesceDate(LocalDate d) {
        return d != null ? d : LocalDate.now();
    }

    private TtSeriesPageParser() {}
}