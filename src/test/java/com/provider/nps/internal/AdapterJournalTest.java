package com.provider.nps.internal;

import static org.junit.jupiter.api.Assertions.*;

import io.grpc.StatusRuntimeException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AdapterJournalTest {
    @TempDir
    Path directory;
    private static final String KEY = "a".repeat(64);
    private static final String HASH = "b".repeat(64);
    private AdapterJournal journal() {
        var journal = new AdapterJournal();
        journal.directory = directory.resolve("inbox").toString();
        return journal;
    }
    @Test
    void completedReplySurvivesANewJournalInstanceAndIsReplayedWithoutProcessing() {
        assertEquals("<reply>stable-id</reply>", journal().execute(KEY, HASH, () -> "<reply>stable-id</reply>"));
        assertEquals("<reply>stable-id</reply>", journal().execute(KEY, HASH, () -> {
            fail("Duplicate must not be processed");
            return "";
        }));
    }
    @Test
    void changedPayloadIsRejectedEvenAfterAnUncertainCall() {
        assertThrows(IllegalStateException.class, () -> journal().execute(KEY, HASH, () -> {
            throw new IllegalStateException("timeout after commit");
        }));
        assertThrows(InboundConflictException.class, () -> journal().execute(KEY, "c".repeat(64), () -> {
            fail("Conflict must not be processed");
            return "";
        }));
        assertEquals("recovered", journal().execute(KEY, HASH, () -> "recovered"));
    }
    @Test
    void crashAfterInternalCommitCanRecoverByTheSameKeyWithoutRepeatingTheFinancialEffect() {
        var financialEffects = new AtomicInteger();
        var internalResults = new java.util.HashMap<String, String>();
        assertThrows(IllegalStateException.class, () -> journal().execute(KEY, HASH, () -> {
            internalResults.computeIfAbsent(KEY, key -> {
                financialEffects.incrementAndGet();
                return "approved";
            });
            throw new IllegalStateException("Process stopped after internal commit");
        }));
        String result = journal().execute(KEY, HASH, () -> internalResults.computeIfAbsent(KEY, key -> {
            financialEffects.incrementAndGet();
            return "approved";
        }));
        assertEquals("approved", result);
        assertEquals(1, financialEffects.get());
    }
    @Test
    void concurrentJournalInstancesDoNotProcessTheSameMessageTogether() throws Exception {
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var first = executor.submit(() -> journal().execute(KEY, HASH, () -> {
                started.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS))
                        throw new IllegalStateException("Test timeout");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                return "done";
            }));
            try {
                assertTrue(started.await(5, TimeUnit.SECONDS));
                assertThrows(StatusRuntimeException.class, () -> journal().execute(KEY, HASH, () -> {
                    fail("Concurrent processing");
                    return "";
                }));
            } finally {
                release.countDown();
            }
            assertEquals("done", first.get(5, TimeUnit.SECONDS));
        }
    }
    @Test
    void unavailableAndCorruptStorageFailBeforeProcessing() throws Exception {
        Path blocked = directory.resolve("blocked");
        Files.writeString(blocked, "not a directory");
        var journal = journal();
        journal.directory = blocked.toString();
        assertThrows(StatusRuntimeException.class, () -> journal.execute(KEY, HASH, () -> {
            fail("Storage unavailable");
            return "";
        }));
        var working = journal();
        working.execute(KEY, HASH, () -> "done");
        Files.writeString(Path.of(working.directory).resolve(KEY + ".json"), "corrupt");
        assertThrows(StatusRuntimeException.class, () -> journal().execute(KEY, HASH, () -> {
            fail("Corrupt storage");
            return "";
        }));
    }
    @Test
    void interruptedOutboundSubmissionIsNeverSentAgain() {
        assertThrows(IllegalStateException.class, () -> journal().executeOnce(KEY, HASH, checkpoint -> {
            throw new IllegalStateException("Process stopped before correlation checkpoint");
        }));
        assertThrows(StatusRuntimeException.class, () -> journal().executeOnce(KEY, HASH, checkpoint -> {
            fail("An uncertain outbound submission must not be repeated");
            return "";
        }));
    }
    @Test
    void interruptedOutboundSubmissionReplaysOriginalCorrelationCheckpoint() {
        assertThrows(IllegalStateException.class, () -> journal().executeOnce(KEY, HASH, checkpoint -> {
            checkpoint.accept("pending:original-message-id");
            throw new IllegalStateException("Process stopped after sending");
        }));
        assertEquals("pending:original-message-id", journal().executeOnce(KEY, HASH, checkpoint -> {
            fail("An uncertain outbound submission must not be repeated");
            return "";
        }));
    }
    @Test
    void fingerprintsIgnoreFormattingAndPrefixesButDetectBusinessChanges() {
        String first = "<p:Document xmlns:p='urn:version'><Transfer amount='100.50' currency='NGN'><Name>A &amp; B</Name></Transfer></p:Document>";
        String same = "<q:Document xmlns:q='urn:version'>\n<Transfer currency='NGN' amount='100.50'>\n<Name><![CDATA[A & B]]></Name>\n</Transfer>\n</q:Document>";
        String hash = BusinessFingerprint.of(com.provider.nps.xml.NpsXml.parse(first), "Transfer");
        assertEquals(hash, BusinessFingerprint.of(com.provider.nps.xml.NpsXml.parse(same), "Transfer"));
        assertNotEquals(hash, BusinessFingerprint
                .of(com.provider.nps.xml.NpsXml.parse(first.replace("100.50", "200.50")), "Transfer"));
    }
}
