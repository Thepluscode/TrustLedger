package com.trustledger.reconciliation.casework;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * The pilot posture, as a failing build rather than a sentence in a document: file-based casework reads
 * what a customer uploaded and writes findings. It has no import path to anything that moves, routes or
 * posts money, and none to a message bus.
 */
class CaseworkBoundaryTest {

    private static final List<String> FORBIDDEN = List.of(
        "com.trustledger.rails", "com.trustledger.core.transfer", "com.trustledger.core.ledger",
        "com.trustledger.core.fraud", "com.trustledger.outbox", "org.apache.kafka", "org.springframework.kafka",
        "LedgerService", "TransferOrchestrator", "ExternalPaymentService");

    @Test
    void caseworkNeverReachesTheMoneyPath() throws IOException {
        List<Path> sources = new ArrayList<>();
        for (String root : List.of("src/main/java/com/trustledger/reconciliation/casework",
                                   "src/main/java/com/trustledger/api/ReconciliationCaseController.java")) {
            try (Stream<Path> walk = Files.walk(Path.of(root))) {
                walk.filter(p -> p.toString().endsWith(".java")).forEach(sources::add);
            }
        }
        // A scan that found nothing would pass for the wrong reason.
        assertTrue(sources.size() >= 15, "expected the casework module, found " + sources.size() + " files");

        List<String> violations = new ArrayList<>();
        for (Path p : sources) {
            for (String line : Files.readAllLines(p)) {
                if (!line.startsWith("import ")) continue;
                for (String f : FORBIDDEN) if (line.contains(f)) violations.add(p.getFileName() + ": " + line.trim());
            }
        }
        assertEquals(List.of(), violations);
    }
}
