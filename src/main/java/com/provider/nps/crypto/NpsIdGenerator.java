package com.provider.nps.crypto;

import java.security.SecureRandom;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * NPS ID and timestamp generator.
 * 
 * Per NPS spec:
 * - Message ID (MsgId): 35 characters = sourceID (12) + yyyyMMddHHmmss (14) + random (9)
 * - End-to-End ID: Same format as MsgId
 * - All timestamps in WAT (West Africa Time, UTC+1)
 */
@ApplicationScoped
public class NpsIdGenerator {

    private static final ZoneId WAT_ZONE = ZoneId.of("Africa/Lagos"); // UTC+1
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final DateTimeFormatter ISO_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
    private static final SecureRandom RANDOM = new SecureRandom();

    @ConfigProperty(name = "nps.source-id")
    String sourceId;

    /**
     * Generates a 35-character NPS message ID.
     * Format: sourceID (12) + yyyyMMddHHmmss (14) + random (9)
     */
    public String generateMessageId() {
        String paddedSourceId = padOrTruncate(sourceId, 12);
        String timestamp = ZonedDateTime.now(WAT_ZONE).format(DATE_FORMAT);
        String random = generateRandomDigits(9);

        return paddedSourceId + timestamp + random;
    }

    /**
     * Generates an end-to-end ID (same format as message ID).
     */
    public String generateEndToEndId() {
        return generateMessageId();
    }

    /**
     * Generates a transaction ID for internal tracking.
     */
    public String generateTransactionId() {
        return generateMessageId();
    }

    /**
     * Returns current timestamp in ISO 8601 format with WAT timezone.
     */
    public String currentTimestamp() {
        return ZonedDateTime.now(WAT_ZONE).format(ISO_FORMAT);
    }

    /**
     * Returns current date in YYYY-MM-DD format.
     */
    public String currentDate() {
        return ZonedDateTime.now(WAT_ZONE).toLocalDate().toString();
    }

    /**
     * Returns current date-time in yyyyMMddHHmmss format.
     */
    public String currentDateTimeCompact() {
        return ZonedDateTime.now(WAT_ZONE).format(DATE_FORMAT);
    }

    private String generateRandomDigits(int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(RANDOM.nextInt(10));
        }
        return sb.toString();
    }

    private String padOrTruncate(String value, int length) {
        if (value == null) {
            value = "";
        }
        if (value.length() > length) {
            return value.substring(0, length);
        }
        return String.format("%-" + length + "s", value).replace(' ', '0');
    }
}
