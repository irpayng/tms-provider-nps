package com.provider.nps.internal;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.grpc.Status;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.function.Supplier;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * File-backed inbox: write intent before RPC, store validated reply before
 * acknowledging.
 */
@ApplicationScoped
public class AdapterJournal {
    @ConfigProperty(name = "nps.journal.directory", defaultValue = "data/nps-journal")
    String directory = "data/nps-journal";
    private final ObjectMapper mapper = new ObjectMapper();
    public AdapterJournal() {
    }
    public AdapterJournal(Path directory) {
        this.directory = directory.toString();
    }

    public String execute(String key, String fingerprint, Supplier<String> operation) {
        return run(key, fingerprint, true, checkpoint -> operation.get());
    }

    /** A prepared outbound submission is never automatically sent a second time. */
    public String executeOnce(String key, String fingerprint,
            java.util.function.Function<java.util.function.Consumer<String>, String> operation) {
        return run(key, fingerprint, false, operation);
    }

    private String run(String key, String fingerprint, boolean resumePrepared,
            java.util.function.Function<java.util.function.Consumer<String>, String> operation) {
        if (!key.matches("[0-9a-f]{64}") || !fingerprint.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid journal identity");
        Path root = Path.of(directory).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
            // Lock files are never deleted: replacing them would let another process lock a
            // different inode.
            try (var channel = FileChannel.open(root.resolve(key + ".lock"), StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE)) {
                try (var lock = channel.tryLock()) {
                    if (lock == null)
                        throw unavailable("This callback is already being processed");
                    Path record = root.resolve(key + ".json");
                    if (Files.exists(record)) {
                        Entry entry = mapper.readValue(Files.readAllBytes(record), Entry.class);
                        validate(entry);
                        if (!entry.fingerprint().equals(fingerprint))
                            throw new InboundConflictException();
                        if (entry.state().equals("COMPLETED"))
                            return entry.reply();
                        if (!resumePrepared) {
                            if (entry.reply() != null)
                                return entry.reply();
                            throw unavailable("Previous submission is uncertain; reconciliation is required");
                        }
                    } else {
                        save(record, new Entry(1, fingerprint, "PREPARED", null));
                    }
                    // PREPARED is deliberately retained on any timeout or crash. The service must
                    // resolve the same key.
                    String reply = java.util.Objects.requireNonNull(operation.apply(checkpoint -> {
                        try {
                            save(record, new Entry(1, fingerprint, "PREPARED",
                                    java.util.Objects.requireNonNull(checkpoint)));
                        } catch (IOException e) {
                            throw Status.UNAVAILABLE.withDescription("Cannot record outbound correlation details")
                                    .withCause(e).asRuntimeException();
                        }
                    }), "Reply must not be null");
                    save(record, new Entry(1, fingerprint, "COMPLETED", reply));
                    return reply;
                }
            }
        } catch (OverlappingFileLockException e) {
            throw unavailable("This callback is already being processed");
        } catch (IOException e) {
            throw Status.UNAVAILABLE.withDescription("Callback journal is unavailable").withCause(e)
                    .asRuntimeException();
        }
    }
    private void validate(Entry entry) throws IOException {
        if (entry == null || entry.version() != 1 || entry.fingerprint() == null
                || !entry.fingerprint().matches("[0-9a-f]{64}")
                || !("PREPARED".equals(entry.state()) || "COMPLETED".equals(entry.state()))
                || ("COMPLETED".equals(entry.state()) && entry.reply() == null))
            throw new IOException("Invalid callback journal entry");
    }
    private void save(Path record, Entry entry) throws IOException {
        Path temporary = Files.createTempFile(record.getParent(), "inbox-", ".tmp");
        try {
            byte[] data = mapper.writeValueAsBytes(entry);
            try (var output = FileChannel.open(temporary, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer bytes = ByteBuffer.wrap(data);
                while (bytes.hasRemaining())
                    output.write(bytes);
                output.force(true);
            }
            // No non-atomic fallback: failure must not acknowledge an unrecorded result.
            Files.move(temporary, record, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
    private static io.grpc.StatusRuntimeException unavailable(String message) {
        return Status.UNAVAILABLE.withDescription(message).asRuntimeException();
    }
    public record Entry(int version, String fingerprint, String state, String reply) {
    }
}
