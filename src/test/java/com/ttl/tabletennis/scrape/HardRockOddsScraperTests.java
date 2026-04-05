package com.ttl.tabletennis.scrape;

import com.sun.net.httpserver.HttpServer;
import com.ttl.tabletennis.model.MatchOdds;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HardRockOddsScraperTests {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @Test
    void fetchParsesOfficialPublicTreeResponse() throws Exception {
        String json = """
                {
                  "data": {
                    "betSync": {
                      "sports": [
                        {
                          "name": "Table Tennis",
                          "competitions": [
                            {
                              "name": "TTL Elite Series",
                              "events": [
                                {
                                  "name": "Alice Adams vs Bob Brown",
                                  "live": true,
                                  "startTime": "2026-02-13T20:00:00Z",
                                  "participants": [
                                    {"name": "Alice Adams"},
                                    {"name": "Bob Brown"}
                                  ],
                                  "markets": [
                                    {
                                      "selections": [
                                        {"price": {"decimal": 1.82}},
                                        {"price": {"decimal": 2.06}}
                                      ]
                                    }
                                  ]
                                }
                              ]
                            }
                          ]
                        }
                      ]
                    }
                  }
                }
                """;

        String endpoint = startJsonServer(json);
        HardRockOddsScraper scraper = new HardRockOddsScraper(
                HttpClient.newHttpClient(),
                "https://example.com/home",
                endpoint,
                "",
                "r.fl",
                "web",
                "",
                "",
                true
        );

        List<MatchOdds> rows = scraper.fetch();
        assertEquals(1, rows.size());

        MatchOdds odds = rows.get(0);
        assertEquals("Alice Adams", odds.getPlayerA());
        assertEquals("Bob Brown", odds.getPlayerB());
        assertEquals(1.82, odds.getOddsA(), 0.0001);
        assertEquals(2.06, odds.getOddsB(), 0.0001);
        assertEquals("TTL Elite Series", odds.getCompetitionName());
        assertTrue(odds.isLive());
        assertEquals("HARD_ROCK_PUBLIC", odds.getSource());
    }

    @Test
    void fetchFiltersOutNonTableTennisWhenEnabled() throws Exception {
        String json = """
                {
                  "data": {
                    "betSync": {
                      "sports": [
                        {
                          "name": "Basketball",
                          "competitions": [
                            {
                              "name": "NBA",
                              "events": [
                                {
                                  "name": "A Team vs B Team",
                                  "markets": [
                                    {
                                      "selections": [
                                        {"price": {"decimal": 1.90}},
                                        {"price": {"decimal": 1.90}}
                                      ]
                                    }
                                  ]
                                }
                              ]
                            }
                          ]
                        }
                      ]
                    }
                  }
                }
                """;

        String endpoint = startJsonServer(json);
        HardRockOddsScraper scraper = new HardRockOddsScraper(
                HttpClient.newHttpClient(),
                "https://example.com/home",
                endpoint,
                "",
                "r.fl",
                "web",
                "",
                "",
                true
        );

        List<MatchOdds> rows = scraper.fetch();
        assertTrue(rows.isEmpty());
    }

    @Test
    void fetchKeepsNonTableTennisWhenFilterDisabled() throws Exception {
        String json = """
                {
                  "data": {
                    "betSync": {
                      "sports": [
                        {
                          "name": "Basketball",
                          "competitions": [
                            {
                              "name": "NBA",
                              "events": [
                                {
                                  "name": "A Team vs B Team",
                                  "participants": [
                                    {"name": "A Team"},
                                    {"name": "B Team"}
                                  ],
                                  "markets": [
                                    {
                                      "selections": [
                                        {"price": {"decimal": 1.90}},
                                        {"price": {"decimal": 1.92}}
                                      ]
                                    }
                                  ]
                                }
                              ]
                            }
                          ]
                        }
                      ]
                    }
                  }
                }
                """;

        String endpoint = startJsonServer(json);
        HardRockOddsScraper scraper = new HardRockOddsScraper(
                HttpClient.newHttpClient(),
                "https://example.com/home",
                endpoint,
                "",
                "r.fl",
                "web",
                "",
                "",
                false
        );

        List<MatchOdds> rows = scraper.fetch();
        assertFalse(rows.isEmpty());
        assertEquals("A Team", rows.get(0).getPlayerA());
    }

    @Test
    void fetchUsesGraphQlOddsFeedWhenConfigured() throws Exception {
        String treeJson = """
                {
                  "data": {
                    "betSync": {
                      "sports": [
                        {
                          "code": "TABLE_TENNIS",
                          "categories": [
                            {
                              "name": "International",
                              "competitions": [
                                {
                                  "id": "754964912222535699",
                                  "name": "TT Elite Series",
                                  "events": {"count": 14}
                                }
                              ]
                            }
                          ]
                        }
                      ]
                    }
                  }
                }
                """;
        String gqlJson = """
                {
                  "data": {
                    "betSync": {
                      "events": {
                        "data": [
                          {
                            "id": "111",
                            "compName": "TT Elite Series",
                            "name": "Alice Adams vs Bob Brown",
                            "eventTime": 1771005600000,
                            "sport": "TABLE_TENNIS",
                            "inplay": true,
                            "displayed": true,
                            "markets": [
                              {
                                "id": "222",
                                "name": "Winner",
                                "type": "TABLE_TENNIS:FT:ML",
                                "displayed": true,
                                "state": "OPEN",
                                "selection": [
                                  {"name": "Alice Adams", "type": "A", "displayed": true, "suspended": false, "odds": "1.82"},
                                  {"name": "Bob Brown", "type": "B", "displayed": true, "suspended": false, "odds": "2.06"}
                                ]
                              }
                            ]
                          }
                        ],
                        "count": 1
                      }
                    }
                  }
                }
                """;

        String[] endpoints = startServerWithGraphQl(treeJson, gqlJson);
        HardRockOddsScraper scraper = new HardRockOddsScraper(
                HttpClient.newHttpClient(),
                "https://example.com/home",
                endpoints[0],
                endpoints[1],
                "",
                "us",
                "enus",
                80,
                "",
                "r.fl",
                "FLORIDA_ONLINE",
                "",
                "",
                true,
                true
        );

        List<MatchOdds> rows = scraper.fetch();
        assertEquals(1, rows.size());
        MatchOdds row = rows.get(0);
        assertEquals("Alice Adams", row.getPlayerA());
        assertEquals("Bob Brown", row.getPlayerB());
        assertEquals(1.82, row.getOddsA(), 0.0001);
        assertEquals(2.06, row.getOddsB(), 0.0001);
        assertEquals("TT Elite Series", row.getCompetitionName());
        assertTrue(row.isLive());
        assertTrue(row.getSource().startsWith("HARD_ROCK_GQL:FLORIDA_ONLINE"));
        assertTrue(row.getSource().contains("|event=111"));
    }

    @Test
    void fetchParsesLiveScoreFromGraphQlMatchState() throws Exception {
        String treeJson = """
                {
                  "data": {
                    "betSync": {
                      "sports": [
                        {
                          "code": "TABLE_TENNIS",
                          "categories": [
                            {
                              "name": "International",
                              "competitions": [
                                {
                                  "id": "754964912222535699",
                                  "name": "TT Elite Series",
                                  "events": {"count": 6}
                                }
                              ]
                            }
                          ]
                        }
                      ]
                    }
                  }
                }
                """;
        String gqlJson = """
                {
                  "data": {
                    "betSync": {
                      "events": {
                        "data": [
                          {
                            "id": "111",
                            "compName": "TT Elite Series",
                            "name": "Alice Adams vs Bob Brown",
                            "eventTime": 1771005600000,
                            "sport": "TABLE_TENNIS",
                            "inplay": true,
                            "isInplay": true,
                            "displayed": true,
                            "state": "ACTIVE",
                            "resulted": false,
                            "matchState": "{\\"TabletennisSimpleMatchState\\":{\\"preMatch\\":false,\\"matchCompleted\\":false,\\"gamesA\\":2,\\"gamesB\\":1,\\"pointsInCurrentGameA\\":8,\\"pointsInCurrentGameB\\":5}}",
                            "markets": [
                              {
                                "id": "222",
                                "name": "Winner",
                                "type": "TABLE_TENNIS:FT:ML",
                                "displayed": true,
                                "state": "OPEN",
                                "selection": [
                                  {"name": "Alice Adams", "type": "A", "displayed": true, "suspended": false, "odds": "1.82"},
                                  {"name": "Bob Brown", "type": "B", "displayed": true, "suspended": false, "odds": "2.06"}
                                ]
                              }
                            ]
                          }
                        ],
                        "count": 1
                      }
                    }
                  }
                }
                """;

        String[] endpoints = startServerWithGraphQl(treeJson, gqlJson);
        HardRockOddsScraper scraper = new HardRockOddsScraper(
                HttpClient.newHttpClient(),
                "https://example.com/home",
                endpoints[0],
                endpoints[1],
                "",
                "us",
                "enus",
                80,
                "",
                "r.fl",
                "FLORIDA_ONLINE",
                "",
                "",
                true,
                true
        );

        List<MatchOdds> rows = scraper.fetch();
        assertEquals(1, rows.size());
        MatchOdds row = rows.get(0);
        assertEquals("2-1 (8-5)", row.getLiveScore());
        assertEquals("LIVE_MID", row.getMatchPhase());
    }

    @Test
    void fetchScoreboardParsesGraphQlEventEvenWhenWinnerMarketIsMissing() throws Exception {
        String treeJson = """
                {
                  "data": {
                    "betSync": {
                      "sports": [
                        {
                          "code": "TABLE_TENNIS",
                          "categories": [
                            {
                              "name": "International",
                              "competitions": [
                                {
                                  "id": "754964912222535699",
                                  "name": "TT Elite Series",
                                  "events": {"count": 6}
                                }
                              ]
                            }
                          ]
                        }
                      ]
                    }
                  }
                }
                """;
        String gqlJson = """
                {
                  "data": {
                    "betSync": {
                      "events": {
                        "data": [
                          {
                            "id": "111",
                            "compName": "TT Elite Series",
                            "name": "Alice Adams vs Bob Brown",
                            "eventTime": 1771005600000,
                            "sport": "TABLE_TENNIS",
                            "inplay": true,
                            "isInplay": true,
                            "displayed": true,
                            "state": "ACTIVE",
                            "resulted": false,
                            "matchState": "{\\"TabletennisSimpleMatchState\\":{\\"preMatch\\":false,\\"matchCompleted\\":false,\\"gamesA\\":2,\\"gamesB\\":1,\\"pointsInCurrentGameA\\":8,\\"pointsInCurrentGameB\\":5}}",
                            "markets": []
                          }
                        ],
                        "count": 1
                      }
                    }
                  }
                }
                """;

        String[] endpoints = startServerWithGraphQl(treeJson, gqlJson);
        HardRockOddsScraper scraper = new HardRockOddsScraper(
                HttpClient.newHttpClient(),
                "https://example.com/home",
                endpoints[0],
                endpoints[1],
                "",
                "us",
                "enus",
                80,
                "",
                "r.fl",
                "FLORIDA_ONLINE",
                "",
                "",
                true,
                true
        );

        assertTrue(scraper.fetch().isEmpty());

        List<MatchOdds> scoreboardRows = scraper.fetchScoreboard();
        assertEquals(1, scoreboardRows.size());
        MatchOdds row = scoreboardRows.get(0);
        assertEquals("Alice Adams", row.getPlayerA());
        assertEquals("Bob Brown", row.getPlayerB());
        assertEquals("2-1 (8-5)", row.getLiveScore());
        assertEquals("LIVE_MID", row.getMatchPhase());
        assertTrue(row.getSource().startsWith("HARD_ROCK_GQL_SCORE:"));
    }

    @Test
    void fetchScoreboardTreatsScoredButNonInplayRowsAsLiveContext() throws Exception {
        String treeJson = """
                {
                  "data": {
                    "betSync": {
                      "sports": [
                        {
                          "code": "TABLE_TENNIS",
                          "categories": [
                            {
                              "name": "International",
                              "competitions": [
                                {
                                  "id": "754964912222535699",
                                  "name": "TT Elite Series",
                                  "events": {"count": 6}
                                }
                              ]
                            }
                          ]
                        }
                      ]
                    }
                  }
                }
                """;
        String gqlJson = """
                {
                  "data": {
                    "betSync": {
                      "events": {
                        "data": [
                          {
                            "id": "111",
                            "compName": "TT Elite Series",
                            "name": "Alice Adams vs Bob Brown",
                            "eventTime": 1771005600000,
                            "sport": "TABLE_TENNIS",
                            "inplay": false,
                            "isInplay": false,
                            "displayed": true,
                            "state": "ACTIVE",
                            "resulted": false,
                            "matchState": "{\\"TabletennisSimpleMatchState\\":{\\"preMatch\\":false,\\"matchCompleted\\":false,\\"gamesA\\":2,\\"gamesB\\":2,\\"pointsInCurrentGameA\\":5,\\"pointsInCurrentGameB\\":10}}",
                            "markets": []
                          }
                        ],
                        "count": 1
                      }
                    }
                  }
                }
                """;

        String[] endpoints = startServerWithGraphQl(treeJson, gqlJson);
        HardRockOddsScraper scraper = new HardRockOddsScraper(
                HttpClient.newHttpClient(),
                "https://example.com/home",
                endpoints[0],
                endpoints[1],
                "",
                "us",
                "enus",
                80,
                "",
                "r.fl",
                "FLORIDA_ONLINE",
                "",
                "",
                true,
                true
        );

        List<MatchOdds> scoreboardRows = scraper.fetchScoreboard();
        assertEquals(1, scoreboardRows.size());
        MatchOdds row = scoreboardRows.get(0);
        assertEquals("2-2 (5-10)", row.getLiveScore());
        assertEquals("LIVE_LATE", row.getMatchPhase());
    }

    @Test
    void fetchScoreboardMergesBroadGraphQlRowsWhenMarketFilteredRowsArePartial() throws Exception {
        String treeJson = """
                {
                  "data": {
                    "betSync": {
                      "sports": [
                        {
                          "code": "TABLE_TENNIS",
                          "categories": [
                            {
                              "name": "International",
                              "competitions": [
                                {
                                  "id": "754964912222535699",
                                  "name": "TT Elite Series",
                                  "events": {"count": 8}
                                }
                              ]
                            }
                          ]
                        }
                      ]
                    }
                  }
                }
                """;
        String filteredJson = """
                {
                  "data": {
                    "betSync": {
                      "events": {
                        "data": [
                          {
                            "id": "111",
                            "compName": "TT Elite Series",
                            "name": "Alice Adams vs Bob Brown",
                            "eventTime": 1771005600000,
                            "sport": "TABLE_TENNIS",
                            "inplay": true,
                            "isInplay": true,
                            "displayed": true,
                            "state": "ACTIVE",
                            "resulted": false,
                            "matchState": "{\\"TabletennisSimpleMatchState\\":{\\"preMatch\\":false,\\"matchCompleted\\":false,\\"gamesA\\":2,\\"gamesB\\":1,\\"pointsInCurrentGameA\\":8,\\"pointsInCurrentGameB\\":5}}",
                            "markets": [
                              {
                                "id": "222",
                                "name": "Winner",
                                "type": "TABLE_TENNIS:FT:ML",
                                "displayed": true,
                                "state": "OPEN",
                                "selection": [
                                  {"name": "Alice Adams", "type": "A", "displayed": true, "suspended": false, "odds": "1.82"},
                                  {"name": "Bob Brown", "type": "B", "displayed": true, "suspended": false, "odds": "2.06"}
                                ]
                              }
                            ]
                          }
                        ],
                        "count": 1
                      }
                    }
                  }
                }
                """;
        String broadJson = """
                {
                  "data": {
                    "betSync": {
                      "events": {
                        "data": [
                          {
                            "id": "111",
                            "compName": "TT Elite Series",
                            "name": "Alice Adams vs Bob Brown",
                            "eventTime": 1771005600000,
                            "sport": "TABLE_TENNIS",
                            "inplay": true,
                            "isInplay": true,
                            "displayed": true,
                            "state": "ACTIVE",
                            "resulted": false,
                            "matchState": "{\\"TabletennisSimpleMatchState\\":{\\"preMatch\\":false,\\"matchCompleted\\":false,\\"gamesA\\":2,\\"gamesB\\":1,\\"pointsInCurrentGameA\\":8,\\"pointsInCurrentGameB\\":5}}",
                            "markets": []
                          },
                          {
                            "id": "222",
                            "compName": "TT Elite Series",
                            "name": "Henryk Tkaczyk vs Pawel Chojnacki",
                            "eventTime": 1771005900000,
                            "sport": "TABLE_TENNIS",
                            "inplay": true,
                            "isInplay": true,
                            "displayed": true,
                            "state": "ACTIVE",
                            "resulted": false,
                            "matchState": "{\\"TabletennisSimpleMatchState\\":{\\"preMatch\\":false,\\"matchCompleted\\":false,\\"gamesA\\":2,\\"gamesB\\":2,\\"pointsInCurrentGameA\\":5,\\"pointsInCurrentGameB\\":10}}",
                            "markets": []
                          }
                        ],
                        "count": 2
                      }
                    }
                  }
                }
                """;

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/events/tree", exchange -> {
            byte[] bytes = treeJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        AtomicInteger gqlCalls = new AtomicInteger();
        server.createContext("/graphql", exchange -> {
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String responseBody = requestBody.contains("\"marketTypes\":[\"TABLE_TENNIS:FT:ML\"]")
                    ? filteredJson
                    : broadJson;
            gqlCalls.incrementAndGet();
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();

        String root = "http://localhost:" + server.getAddress().getPort();
        HardRockOddsScraper scraper = new HardRockOddsScraper(
                HttpClient.newHttpClient(),
                "https://example.com/home",
                root + "/events/tree",
                root + "/graphql",
                "",
                "us",
                "enus",
                80,
                "",
                "r.fl",
                "FLORIDA_ONLINE",
                "",
                "",
                true,
                true
        );

        List<MatchOdds> rows = scraper.fetchScoreboard();
        assertEquals(2, rows.size());
        assertTrue(rows.stream().anyMatch(r -> "Alice Adams".equals(r.getPlayerA()) && "Bob Brown".equals(r.getPlayerB())));
        assertTrue(rows.stream().anyMatch(r -> "Henryk Tkaczyk".equals(r.getPlayerA()) && "Pawel Chojnacki".equals(r.getPlayerB())));
        assertTrue(gqlCalls.get() >= 2);
    }

    @Test
    void fetchScoreboardMergesGraphQlAndPublicTreeWhenGraphQlIsPartial() throws Exception {
        String treeJson = """
                {
                  "data": {
                    "betSync": {
                      "sports": [
                        {
                          "name": "Table Tennis",
                          "competitions": [
                            {
                              "name": "TT Elite Series",
                              "events": [
                                {
                                  "name": "Alice Adams vs Bob Brown",
                                  "live": true,
                                  "startTime": "2026-02-17T19:30:00Z",
                                  "scoreDisplay": "2-1 (8-5)"
                                },
                                {
                                  "name": "Henryk Tkaczyk vs Pawel Chojnacki",
                                  "live": true,
                                  "startTime": "2026-02-17T19:45:00Z",
                                  "scoreDisplay": "2-2 (5-10)"
                                }
                              ]
                            }
                          ]
                        }
                      ]
                    }
                  }
                }
                """;
        String gqlJson = """
                {
                  "data": {
                    "betSync": {
                      "events": {
                        "data": [
                          {
                            "id": "111",
                            "compName": "TT Elite Series",
                            "name": "Alice Adams vs Bob Brown",
                            "eventTime": 1771356600000,
                            "sport": "TABLE_TENNIS",
                            "inplay": true,
                            "isInplay": true,
                            "displayed": true,
                            "state": "ACTIVE",
                            "resulted": false,
                            "matchState": "{\\"TabletennisSimpleMatchState\\":{\\"preMatch\\":false,\\"matchCompleted\\":false,\\"gamesA\\":2,\\"gamesB\\":1,\\"pointsInCurrentGameA\\":8,\\"pointsInCurrentGameB\\":5}}",
                            "markets": []
                          }
                        ],
                        "count": 1
                      }
                    }
                  }
                }
                """;

        String[] endpoints = startServerWithGraphQl(treeJson, gqlJson);
        HardRockOddsScraper scraper = new HardRockOddsScraper(
                HttpClient.newHttpClient(),
                "https://example.com/home",
                endpoints[0],
                endpoints[1],
                "",
                "us",
                "enus",
                80,
                "",
                "r.fl",
                "FLORIDA_ONLINE",
                "",
                "",
                true,
                true
        );

        List<MatchOdds> rows = scraper.fetchScoreboard();
        assertEquals(2, rows.size());
        assertTrue(rows.stream().anyMatch(r -> "Alice Adams".equals(r.getPlayerA()) && "Bob Brown".equals(r.getPlayerB())));
        assertTrue(rows.stream().anyMatch(r -> "Henryk Tkaczyk".equals(r.getPlayerA()) && "Pawel Chojnacki".equals(r.getPlayerB())));
    }

    @Test
    void fetchMarksFinishedWhenGraphQlEventResulted() throws Exception {
        String treeJson = """
                {
                  "data": {
                    "betSync": {
                      "sports": [
                        {
                          "code": "TABLE_TENNIS",
                          "categories": [
                            {
                              "name": "International",
                              "competitions": [
                                {
                                  "id": "754964912222535699",
                                  "name": "TT Elite Series",
                                  "events": {"count": 6}
                                }
                              ]
                            }
                          ]
                        }
                      ]
                    }
                  }
                }
                """;
        String gqlJson = """
                {
                  "data": {
                    "betSync": {
                      "events": {
                        "data": [
                          {
                            "id": "111",
                            "compName": "TT Elite Series",
                            "name": "Alice Adams vs Bob Brown",
                            "eventTime": 1771005600000,
                            "sport": "TABLE_TENNIS",
                            "inplay": false,
                            "isInplay": false,
                            "displayed": true,
                            "state": "CLOSED",
                            "resulted": true,
                            "matchState": "{\\"TabletennisSimpleMatchState\\":{\\"preMatch\\":false,\\"matchCompleted\\":true,\\"gamesA\\":3,\\"gamesB\\":1}}",
                            "markets": [
                              {
                                "id": "222",
                                "name": "Winner",
                                "type": "TABLE_TENNIS:FT:ML",
                                "displayed": true,
                                "state": "CLOSED",
                                "resulted": true,
                                "selection": [
                                  {"name": "Alice Adams", "type": "A", "displayed": true, "suspended": false, "resulted": true, "result": "WIN", "odds": "1.82"},
                                  {"name": "Bob Brown", "type": "B", "displayed": true, "suspended": false, "resulted": true, "result": "LOSE", "odds": "2.06"}
                                ]
                              }
                            ]
                          }
                        ],
                        "count": 1
                      }
                    }
                  }
                }
                """;

        String[] endpoints = startServerWithGraphQl(treeJson, gqlJson);
        HardRockOddsScraper scraper = new HardRockOddsScraper(
                HttpClient.newHttpClient(),
                "https://example.com/home",
                endpoints[0],
                endpoints[1],
                "",
                "us",
                "enus",
                80,
                "",
                "r.fl",
                "FLORIDA_ONLINE",
                "",
                "",
                true,
                true
        );

        List<MatchOdds> rows = scraper.fetch();
        assertEquals(1, rows.size());
        MatchOdds row = rows.get(0);
        assertEquals("3-1", row.getLiveScore());
        assertEquals("FINISHED", row.getMatchPhase());
    }

    @Test
    void fetchOmitsPrematchZeroZeroScore() throws Exception {
        String treeJson = """
                {
                  "data": {
                    "betSync": {
                      "sports": [
                        {
                          "code": "TABLE_TENNIS",
                          "categories": [
                            {
                              "name": "International",
                              "competitions": [
                                {
                                  "id": "754964912222535699",
                                  "name": "TT Elite Series",
                                  "events": {"count": 6}
                                }
                              ]
                            }
                          ]
                        }
                      ]
                    }
                  }
                }
                """;
        String gqlJson = """
                {
                  "data": {
                    "betSync": {
                      "events": {
                        "data": [
                          {
                            "id": "111",
                            "compName": "TT Elite Series",
                            "name": "Alice Adams vs Bob Brown",
                            "eventTime": 1771005600000,
                            "sport": "TABLE_TENNIS",
                            "inplay": false,
                            "isInplay": false,
                            "displayed": true,
                            "state": "ACTIVE",
                            "resulted": false,
                            "matchState": "{\\"TabletennisSimpleMatchState\\":{\\"preMatch\\":true,\\"matchCompleted\\":false,\\"gamesA\\":0,\\"gamesB\\":0,\\"pointsInCurrentGameA\\":0,\\"pointsInCurrentGameB\\":0}}",
                            "markets": [
                              {
                                "id": "222",
                                "name": "Winner",
                                "type": "TABLE_TENNIS:FT:ML",
                                "displayed": true,
                                "state": "OPEN",
                                "selection": [
                                  {"name": "Alice Adams", "type": "A", "displayed": true, "suspended": false, "odds": "1.82"},
                                  {"name": "Bob Brown", "type": "B", "displayed": true, "suspended": false, "odds": "2.06"}
                                ]
                              }
                            ]
                          }
                        ],
                        "count": 1
                      }
                    }
                  }
                }
                """;

        String[] endpoints = startServerWithGraphQl(treeJson, gqlJson);
        HardRockOddsScraper scraper = new HardRockOddsScraper(
                HttpClient.newHttpClient(),
                "https://example.com/home",
                endpoints[0],
                endpoints[1],
                "",
                "us",
                "enus",
                80,
                "",
                "r.fl",
                "FLORIDA_ONLINE",
                "",
                "",
                true,
                true
        );

        List<MatchOdds> rows = scraper.fetch();
        assertEquals(1, rows.size());
        MatchOdds row = rows.get(0);
        assertEquals("", row.getLiveScore());
        assertEquals("UPCOMING", row.getMatchPhase());
    }

    @Test
    void fetchScoreboardByEventIdsQueriesIdFilterAndReturnsTrackedEvent() throws Exception {
        String treeJson = """
                {
                  "data": {
                    "betSync": {
                      "sports": [
                        {
                          "code": "TABLE_TENNIS",
                          "categories": [
                            {
                              "name": "International",
                              "competitions": [
                                {
                                  "id": "754964912222535699",
                                  "name": "TT Elite Series",
                                  "events": {"count": 8}
                                }
                              ]
                            }
                          ]
                        }
                      ]
                    }
                  }
                }
                """;
        String gqlJson = """
                {
                  "data": {
                    "betSync": {
                      "events": {
                        "data": [
                          {
                            "id": "tracked-222",
                            "compName": "TT Elite Series",
                            "name": "Henryk Tkaczyk vs Pawel Chojnacki",
                            "eventTime": 1771005900000,
                            "sport": "TABLE_TENNIS",
                            "inplay": true,
                            "isInplay": true,
                            "displayed": true,
                            "state": "ACTIVE",
                            "resulted": false,
                            "matchState": "{\\"TabletennisSimpleMatchState\\":{\\"preMatch\\":false,\\"matchCompleted\\":false,\\"gamesA\\":2,\\"gamesB\\":2,\\"pointsInCurrentGameA\\":5,\\"pointsInCurrentGameB\\":10}}",
                            "markets": []
                          }
                        ],
                        "count": 1
                      }
                    }
                  }
                }
                """;

        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/events/tree", exchange -> {
            byte[] bytes = treeJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        AtomicReference<String> lastRequestBody = new AtomicReference<>("");
        server.createContext("/graphql", exchange -> {
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastRequestBody.set(requestBody);
            byte[] bytes = gqlJson.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();

        String root = "http://localhost:" + server.getAddress().getPort();
        HardRockOddsScraper scraper = new HardRockOddsScraper(
                HttpClient.newHttpClient(),
                "https://example.com/home",
                root + "/events/tree",
                root + "/graphql",
                "",
                "us",
                "enus",
                80,
                "",
                "r.fl",
                "FLORIDA_ONLINE",
                "",
                "",
                true,
                true
        );

        List<MatchOdds> rows = scraper.fetchScoreboardByEventIds(List.of("tracked-222"));
        assertEquals(1, rows.size());
        MatchOdds row = rows.get(0);
        assertEquals("Henryk Tkaczyk", row.getPlayerA());
        assertEquals("Pawel Chojnacki", row.getPlayerB());
        assertTrue(row.getSource().contains("|event=tracked-222"));
        assertEquals("tracked-222", row.getExternalEventId());
        assertEquals("GQL_TRACKED_EVENT", row.getSourceType());
        assertEquals(0.97, row.getSourceConfidence(), 0.0001);
        assertTrue(row.isDisplayed());
        assertFalse(row.isResulted());
        assertFalse(row.isMatchCompleted());
        assertTrue(lastRequestBody.get().contains("\"field\":\"id\""));
        assertTrue(lastRequestBody.get().contains("\"tracked-222\""));
    }

    @Test
    void fetchScoreboardToleratesCountOnlyPublicTreeWhenGraphQlIsEmpty() throws Exception {
        String treeJson = """
                {
                  "data": {
                    "betSync": {
                      "sports": [
                        {
                          "code": "TABLE_TENNIS",
                          "categories": [
                            {
                              "name": "International",
                              "competitions": [
                                {
                                  "id": "754964912222535699",
                                  "name": "TT Elite Series",
                                  "events": {"count": 8}
                                }
                              ]
                            }
                          ]
                        }
                      ]
                    }
                  }
                }
                """;
        String gqlJson = """
                {
                  "data": {
                    "betSync": {
                      "events": {
                        "data": [],
                        "count": 0
                      }
                    }
                  }
                }
                """;

        String[] endpoints = startServerWithGraphQl(treeJson, gqlJson);
        HardRockOddsScraper scraper = new HardRockOddsScraper(
                HttpClient.newHttpClient(),
                "https://example.com/home",
                endpoints[0],
                endpoints[1],
                "",
                "us",
                "enus",
                80,
                "",
                "r.fl",
                "FLORIDA_ONLINE",
                "",
                "",
                true,
                true
        );

        List<MatchOdds> rows = scraper.fetchScoreboard();
        assertTrue(rows.isEmpty());
    }

    private String startJsonServer(String body) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/events/tree", exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        return "http://localhost:" + server.getAddress().getPort() + "/events/tree";
    }

    private String[] startServerWithGraphQl(String treeBody, String gqlBody) throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/events/tree", exchange -> {
            byte[] bytes = treeBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.createContext("/graphql", exchange -> {
            byte[] bytes = gqlBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        String root = "http://localhost:" + server.getAddress().getPort();
        return new String[]{root + "/events/tree", root + "/graphql"};
    }
}
