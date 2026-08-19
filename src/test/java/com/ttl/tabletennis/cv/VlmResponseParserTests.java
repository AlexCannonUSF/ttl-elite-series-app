package com.ttl.tabletennis.cv;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VlmResponseParserTests {

    private final VlmResponseParser parser = new VlmResponseParser(new ObjectMapper());

    @Test
    void parseAcceptsCanonicalJsonObject() {
        VlmResponseParser.ParseOutcome outcome = parser.parse(
                "{\"topGames\":2,\"botGames\":1,\"topPoints\":7,\"botPoints\":5,\"server\":\"TOP\",\"confidence\":0.92}"
        );

        assertEquals(VlmResponseParser.ParseStatus.OK, outcome.status());
        VlmScoreReading reading = outcome.reading().orElseThrow();
        assertEquals(2, reading.topGames());
        assertEquals(1, reading.botGames());
        assertEquals(7, reading.topPoints());
        assertEquals(5, reading.botPoints());
        assertEquals(ServerSide.TOP, reading.server());
        assertEquals(0.92, reading.confidence());
    }

    @Test
    void parseStripsMarkdownFenceAndExtractsObject() {
        VlmResponseParser.ParseOutcome outcome = parser.parse(
                "```json\n{\"topGames\":0,\"botGames\":0,\"topPoints\":11,\"botPoints\":9,\"server\":\"BOT\",\"confidence\":0.81}\n```"
        );

        assertEquals(VlmResponseParser.ParseStatus.OK, outcome.status());
        assertEquals(ServerSide.BOT, outcome.reading().orElseThrow().server());
    }

    @Test
    void parseMapsUnreadableErrorObjectToUnreadableStatus() {
        VlmResponseParser.ParseOutcome outcome = parser.parse("{\"error\":\"UNREADABLE\"}");

        assertEquals(VlmResponseParser.ParseStatus.UNREADABLE, outcome.status());
        assertTrue(outcome.reading().isEmpty());
    }

    @Test
    void parseRejectsMissingRequiredField() {
        VlmResponseParser.ParseOutcome outcome = parser.parse(
                "{\"topGames\":1,\"botGames\":1,\"topPoints\":3,\"confidence\":0.8}"
        );

        assertEquals(VlmResponseParser.ParseStatus.MALFORMED, outcome.status());
        assertTrue(outcome.error().contains("botPoints"));
    }

    @Test
    void parseRejectsOutOfRangeConfidence() {
        VlmResponseParser.ParseOutcome outcome = parser.parse(
                "{\"topGames\":0,\"botGames\":0,\"topPoints\":0,\"botPoints\":0,\"server\":\"UNKNOWN\",\"confidence\":1.5}"
        );

        assertEquals(VlmResponseParser.ParseStatus.MALFORMED, outcome.status());
        assertTrue(outcome.error().contains("confidence"));
    }

    @Test
    void parseRejectsNonIntegerScores() {
        VlmResponseParser.ParseOutcome outcome = parser.parse(
                "{\"topGames\":\"two\",\"botGames\":1,\"topPoints\":0,\"botPoints\":0,\"confidence\":0.9}"
        );

        assertEquals(VlmResponseParser.ParseStatus.MALFORMED, outcome.status());
    }

    @Test
    void parseRejectsNegativeScores() {
        VlmResponseParser.ParseOutcome outcome = parser.parse(
                "{\"topGames\":-1,\"botGames\":0,\"topPoints\":0,\"botPoints\":0,\"confidence\":0.9}"
        );

        assertEquals(VlmResponseParser.ParseStatus.MALFORMED, outcome.status());
    }

    @Test
    void parseHandlesNullAndBlank() {
        assertEquals(VlmResponseParser.ParseStatus.MALFORMED, parser.parse(null).status());
        assertEquals(VlmResponseParser.ParseStatus.MALFORMED, parser.parse("   ").status());
    }

    @Test
    void parseRejectsNonObjectRoot() {
        assertEquals(VlmResponseParser.ParseStatus.MALFORMED, parser.parse("[1,2,3]").status());
    }

    @Test
    void parseDefaultsUnknownServer() {
        VlmResponseParser.ParseOutcome outcome = parser.parse(
                "{\"topGames\":0,\"botGames\":0,\"topPoints\":3,\"botPoints\":4,\"confidence\":0.7}"
        );

        assertEquals(VlmResponseParser.ParseStatus.OK, outcome.status());
        assertEquals(ServerSide.UNKNOWN, outcome.reading().orElseThrow().server());
    }
}
