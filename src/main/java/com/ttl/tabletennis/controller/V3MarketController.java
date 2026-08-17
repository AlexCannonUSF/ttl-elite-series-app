package com.ttl.tabletennis.controller;

import com.ttl.tabletennis.domain.MarketBook;
import com.ttl.tabletennis.dto.MarketIntelligenceDto;
import com.ttl.tabletennis.repository.MarketBookRepository;
import com.ttl.tabletennis.service.MarketIntelligenceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v3/market")
public class V3MarketController {
    private final MarketIntelligenceService marketService;
    private final MarketBookRepository bookRepository;

    public V3MarketController(MarketIntelligenceService marketService, MarketBookRepository bookRepository) {
        this.marketService = marketService;
        this.bookRepository = bookRepository;
    }

    @GetMapping
    public MarketIntelligenceDto market(@RequestParam String identity,
                                        @RequestParam(defaultValue = "400") int historyLimit) {
        return marketService.market(identity, historyLimit);
    }

    @GetMapping("/books")
    public List<MarketBook> books() {
        return bookRepository.findByEnabledTrueOrderByDisplayNameAsc();
    }
}
