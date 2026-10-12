package org.simulator.regression;

import org.simulator.acquirer.TransactionOutcome;
import org.simulator.iso.IsoMessage;
import org.simulator.iso.MessageService;
import org.simulator.persistence.repo.TestCaseRecord;
import org.simulator.persistence.repo.TestCaseRepository;

/**
 * Captures executed transactions as regression test cases. The request is stored unmasked
 * (it must be replayable), except PIN block and security control data which are removed: use test cards only.
 */
public final class TestCaseCapture {

    private TestCaseCapture() {
    }

    public static long capture(TestCaseRepository repo, MessageService messages, String suiteId, String name,
                               TransactionOutcome outcome) {
        IsoMessage req = outcome.request().replayCopy();
        String expectedRc = outcome.responseCode() != null ? outcome.responseCode() : "00";
        TestCaseRecord tc = TestCaseRecord.of(suiteId, name, req.getDialect(), messages.toJson(req), expectedRc)
                .withSource("CAPTURE");
        return repo.save(tc);
    }
}
