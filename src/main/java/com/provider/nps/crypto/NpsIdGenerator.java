package com.provider.nps.crypto;

import jakarta.enterprise.context.ApplicationScoped;
import java.security.SecureRandom;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * NPS ID and timestamp generator.
 * 
 * Per NPS spec: - Message ID (MsgId): 35 characters = sourceID (6) +
 * yyyyMMddHHmmss (14) + random (15) - End-to-End ID: Same format as MsgId - All
 * timestamps in WAT (West Africa Time, UTC+1)
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
     * Generates a 35-character NPS message ID. Format: sourceID (6) +
     * yyyyMMddHHmmss (14) + random (15)
     */
    public String generateMessageId() {
        if (sourceId == null || !sourceId.matches("[0-9]{6}")) {
            throw new IllegalArgumentException("NPS_SOURCE_ID must contain exactly six digits");
        }
        String timestamp = ZonedDateTime.now(WAT_ZONE).format(DATE_FORMAT);
        String random = generateRandomDigits(15);

        return sourceId + timestamp + random;
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

}
