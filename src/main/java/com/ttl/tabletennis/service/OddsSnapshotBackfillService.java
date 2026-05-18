package com.ttl.tabletennis.service;

import com.ttl.tabletennis.domain.OddsQuote;
import com.ttl.tabletennis.domain.OddsSnapshot;
import com.ttl.tabletennis.repository.OddsQuoteRepository;
import com.ttl.tabletennis.repository.OddsSnapshotRepository;
import com.ttl.tabletennis.scrape.OddsSnapshotFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Slice;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
public class OddsSnapshotBackfillService {

    private final OddsQuoteRepository oddsQuoteRepository;
    private final OddsSnapshotRepository oddsSnapshotRepository;
    private final OddsSnapshotFactory oddsSnapshotFactory;

    public OddsSnapshotBackfillService(OddsQuoteRepository oddsQuoteRepository,
                                       OddsSnapshotRepository oddsSnapshotRepository,
                                       OddsSnapshotFactory oddsSnapshotFactory) {
        this.oddsQuoteRepository = oddsQuoteRepository;
        this.oddsSnapshotRepository = oddsSnapshotRepository;
        this.oddsSnapshotFactory = oddsSnapshotFactory;
    }

    public BackfillResult backfillHistoricalQuotes(int batchSize, int maxPages) {
        int safeBatchSize = Math.max(1, Math.min(batchSize, 2_000));
        int safeMaxPages = Math.max(0, maxPages);

        int pageNumber = 0;
        int scannedQuotes = 0;
        int eligibleQuotes = 0;
        int persistedSnapshots = 0;
        int skippedSnapshots = 0;

        Slice<OddsQuote> page;
        do {
            page = oddsQuoteRepository.findAllByOrderByScrapedAtAscIdAsc(PageRequest.of(pageNumber, safeBatchSize));
            for (OddsQuote oddsQuote : page.getContent()) {
                scannedQuotes++;
                if (!oddsSnapshotFactory.supportsOddsQuoteBackfill(oddsQuote)) {
                    continue;
                }
                eligibleQuotes++;
                List<OddsSnapshot> candidateSnapshots = oddsSnapshotFactory.fromOddsQuote(oddsQuote);
                List<OddsSnapshot> toPersist = new ArrayList<>();
                for (OddsSnapshot snapshot : candidateSnapshots) {
                    if (snapshot == null) {
                        continue;
                    }
                    if (oddsSnapshotRepository.existsByTrackedEventIdAndSideAndObservedAtAndPriceDecimalAndSourceId(
                            snapshot.getTrackedEventId(),
                            snapshot.getSide(),
                            snapshot.getObservedAt(),
                            snapshot.getPriceDecimal(),
                            snapshot.getSourceId()
                    )) {
                        skippedSnapshots++;
                        continue;
                    }
                    toPersist.add(snapshot);
                }
                if (!toPersist.isEmpty()) {
                    oddsSnapshotRepository.saveAll(toPersist);
                    persistedSnapshots += toPersist.size();
                }
            }
            pageNumber++;
        } while (page.hasNext() && (safeMaxPages == 0 || pageNumber < safeMaxPages));

        return new BackfillResult(scannedQuotes, eligibleQuotes, persistedSnapshots, skippedSnapshots, pageNumber);
    }

    public record BackfillResult(int scannedQuotes,
                                 int eligibleQuotes,
                                 int persistedSnapshots,
                                 int skippedSnapshots,
                                 int pagesProcessed) {
    }
}
