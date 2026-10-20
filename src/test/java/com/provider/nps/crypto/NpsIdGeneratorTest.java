package com.provider.nps.crypto;

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@QuarkusTest
class NpsIdGeneratorTest {

    @Inject
    NpsIdGenerator idGenerator;

    @Test
    @DisplayName("Message ID should be exactly 35 characters")
    void testMessageIdLength() {
        String msgId = idGenerator.generateMessageId();
        assertEquals(35, msgId.length(), "MsgId must be 35 characters");
    }

    @Test
    @DisplayName("Message ID should start with source ID")
    void testMessageIdStartsWithSourceId() {
        String msgId = idGenerator.generateMessageId();
        assertEquals("999057", msgId.substring(0, 6));
        assertDoesNotThrow(() -> java.time.LocalDateTime.parse(msgId.substring(6, 20),
                java.time.format.DateTimeFormatter.ofPattern("uuuuMMddHHmmss")));
        assertTrue(msgId.substring(20).matches("[0-9]{15}"));
    }

    @Test
    @DisplayName("Message IDs should be unique")
    void testMessageIdUniqueness() {
        String msgId1 = idGenerator.generateMessageId();
        String msgId2 = idGenerator.generateMessageId();
        String msgId3 = idGenerator.generateMessageId();

        assertNotEquals(msgId1, msgId2);
        assertNotEquals(msgId2, msgId3);
        assertNotEquals(msgId1, msgId3);
    }

    @Test
    @DisplayName("End-to-End ID should be 35 characters")
    void testEndToEndIdLength() {
        String e2eId = idGenerator.generateEndToEndId();
        assertEquals(35, e2eId.length());
    }

    @Test
    @DisplayName("Timestamp should be in ISO 8601 format with WAT timezone")
    void testTimestampFormat() {
        String timestamp = idGenerator.currentTimestamp();

        // Should contain date and time parts
        assertTrue(timestamp.contains("T"));
        // Should have timezone offset
        assertTrue(timestamp.contains("+01:00") || timestamp.contains("+00:00"));
    }

    @Test
    @DisplayName("Current date should be in YYYY-MM-DD format")
    void testCurrentDateFormat() {
        String date = idGenerator.currentDate();

        // Should match YYYY-MM-DD format
        assertTrue(date.matches("\\d{4}-\\d{2}-\\d{2}"));
    }

    @Test
    @DisplayName("Compact datetime should be 14 characters (yyyyMMddHHmmss)")
    void testCompactDateTimeFormat() {
        String compact = idGenerator.currentDateTimeCompact();
        assertEquals(14, compact.length());
        assertTrue(compact.matches("\\d{14}"));
    }
    @Test
    void invalidSourceIdIsRejectedWithoutPaddingOrTruncation() {
        var generator = new NpsIdGenerator();
        for (String value : new String[]{null, "12345", "1234567", "ABCDEF", " 999057"}) {
            generator.sourceId = value;
            assertThrows(IllegalArgumentException.class, generator::generateMessageId);
        }
    }
}
