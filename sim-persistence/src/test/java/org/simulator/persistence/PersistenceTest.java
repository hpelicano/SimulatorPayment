package org.simulator.persistence;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.simulator.persistence.repo.RunRepository;
import org.simulator.persistence.repo.TestCaseRecord;
import org.simulator.persistence.repo.TestCaseRepository;
import org.simulator.persistence.repo.TransactionLogRecord;
import org.simulator.persistence.repo.TransactionLogRepository;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PersistenceTest {

    Database db;
    Jdbc jdbc;

    @BeforeEach
    void setUp() {
        db = Database.inMemory("test" + System.nanoTime());
        jdbc = new Jdbc(db);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    @Test
    void testCasesCrud() {
        TestCaseRepository repo = new TestCaseRepository(jdbc);
        String suite = repo.suiteIdOrCreate("smoke");
        long id = repo.save(TestCaseRecord.of(suite, "compra", "base24-pos", "{\"mti\":\"0200\"}", "00"));
        assertThat(repo.bySuite(suite)).hasSize(1);
        assertThat(repo.byId(id)).get().extracting(TestCaseRecord::expectedRc).isEqualTo("00");
        assertThat(repo.suites()).filteredOn(s -> s.id().equals("smoke")).singleElement()
                .extracting(TestCaseRepository.SuiteRecord::caseCount).isEqualTo(1);
    }

    @Test
    void asyncTransactionLog() {
        long initialAuditRows = Long.parseLong(new DbAdminService(db)
                .execute("SELECT COUNT(*) FROM audit_log", 1).rows().get(0).get(0));
        try (TransactionLogRepository repo = new TransactionLogRepository(jdbc)) {
            for (int i = 0; i < 1000; i++) {
                repo.append(new TransactionLogRecord(null, "STRESS", "DEV", "base24-pos", "0200", "000000", String.valueOf(i),
                        null, null, null, null, "00", "APPROVED", 1000L, null, null, null, null, null, null));
            }
            repo.flush();
            assertThat(repo.count()).isEqualTo(1000);
            assertThat(repo.recent(5)).hasSize(5);
            assertThat(new DbAdminService(db).execute("SELECT COUNT(*) FROM audit_log", 1).rows().get(0).get(0))
                    .isEqualTo(String.valueOf(initialAuditRows + 1000));
        }
    }

    @Test
    void adminConsoleAndBackupRestore(@TempDir Path tmp) {
        DbAdminService admin = new DbAdminService(db);
        new RunRepository(jdbc).audit("tester", "LOGIN", "app", null, true);
        assertThat(admin.tables()).contains("UNIT_TEST", "REGRESION", "STRESS_TEST", "TRANSACTION_LOG", "AUDIT_LOG",
                "SYSTEM_AUDIT_LOG");
        assertThat(admin.execute("SELECT COUNT(*) AS N FROM system_audit_log", 10).rows().get(0).get(0)).isEqualTo("1");
        DbAdminService.SqlResult rows = admin.tableRows("SYSTEM_AUDIT_LOG", 10);
        assertThat(rows.columns()).contains("ID", "ACTION", "TARGET");
        assertThat(rows.rows()).singleElement().satisfies(row -> assertThat(row).contains("LOGIN", "app"));
        assertThat(admin.exportTableJson("system_audit_log")).contains("LOGIN");
        admin.createDialect("base24-custom", "Custom");
        admin.createMessageTable("custom_test");
        long messageId = admin.insertMessage("custom_test", "base24-custom", "Compra",
                "{\"dialect\":\"base24-custom\",\"mti\":\"0200\",\"fields\":{}}", "00", "7,11", true);
        assertThat(messageId).isPositive();
        admin.updateMessage("custom_test", messageId, "base24-custom", "Compra actualizada",
                "{\"dialect\":\"base24-custom\",\"mti\":\"0200\",\"fields\":{\"3\":\"000000\"}}",
                "10", "7,11,37", false);
        assertThat(admin.messageTables()).contains("CUSTOM_TEST");
        assertThat(admin.tableRows("CUSTOM_TEST", "base24-custom", 10).rows()).singleElement()
                .satisfies(row -> assertThat(row).contains("Compra actualizada", "10", "false"));

        Path backup = tmp.resolve("backup.zip");
        admin.backup(backup);
        admin.execute("DELETE FROM system_audit_log", 1);
        admin.restore(backup);
        assertThat(admin.execute("SELECT COUNT(*) FROM system_audit_log", 10).rows().get(0).get(0)).isEqualTo("1");
    }

    @Test
    void reusableMessageInsertRequiresRegisteredSuiteAndRequestDialect() {
        DbAdminService admin = new DbAdminService(db);
        long regressionBefore = Long.parseLong(admin.execute("SELECT COUNT(*) FROM regresion", 10).rows().get(0).get(0));
        long unitTestsBefore = Long.parseLong(admin.execute("SELECT COUNT(*) FROM unit_test", 10).rows().get(0).get(0));
        long id = admin.insertMessage("regresion", "base24-regresion", "Compra POS",
                "{\"dialect\":\"base24-pos\",\"mti\":\"0200\",\"fields\":{}}", "00", "", true);
        assertThat(id).isPositive();
        assertThat(admin.execute("SELECT COUNT(*) FROM regresion", 10).rows().get(0).get(0))
                .isEqualTo(String.valueOf(regressionBefore + 1));

        assertThatThrownBy(() -> admin.insertMessage("unit_test", "missing-suite", "Bad suite",
                "{\"dialect\":\"base24-pos\"}", "00", "", true))
                .hasMessageContaining("Unknown suite_id reference");
        assertThatThrownBy(() -> admin.insertMessage("unit_test", "base24-pos", "Bad request dialect",
                "{\"dialect\":\"missing-dialect\"}", "00", "", true))
                .hasMessageContaining("Unknown request.dialect reference");
        assertThatThrownBy(() -> admin.insertMessage("unit_test", "base24-pos", "Invalid JSON",
                "{\"dialect\":", "00", "", true))
                .hasMessageContaining("Invalid request JSON");
        assertThat(admin.execute("SELECT COUNT(*) FROM unit_test", 10).rows().get(0).get(0))
                .isEqualTo(String.valueOf(unitTestsBefore));
    }

    @Test
    void fileDatabasePersistsAcrossReopen(@TempDir Path tmp) {
        try (Database file = Database.file(tmp)) {
            Jdbc fileJdbc = new Jdbc(file);
            new RunRepository(fileJdbc).audit("tester", "OPEN", "file-db", null, true);
            DbAdminService admin = new DbAdminService(file);
            admin.createDialect("base24-persisted", "Persistence test");
            admin.createMessageTable("persisted_test");
            admin.insertMessage("persisted_test", "base24-persisted", "Persistent request",
                    "{\"dialect\":\"base24-persisted\",\"mti\":\"0800\",\"fields\":{\"70\":\"301\"}}",
                    "00", "7,11", true);
            assertThat(admin.execute("SELECT COUNT(*) FROM unit_test WHERE name IN "
                    + "('Base24 POS - Compra EMV (DE55)', 'Visa - Tags anidados (77)', "
                    + "'Mastercard - Respuesta y script (71)', "
                    + "'ISO87 - Tag propietario, longitud larga y repetidos')", 1).rows().get(0).get(0))
                    .isEqualTo("4");
        }
        try (Database file = Database.file(tmp)) {
            DbAdminService admin = new DbAdminService(file);
            assertThat(admin.execute("SELECT COUNT(*) FROM system_audit_log", 10).rows().get(0).get(0))
                    .isEqualTo("1");
            assertThat(admin.tableRows("PERSISTED_TEST", "base24-persisted", 10).rows()).singleElement()
                    .satisfies(row -> assertThat(row).contains("Persistent request", "00"));
            assertThat(admin.execute("SELECT COUNT(*) FROM \"flyway_schema_history\" WHERE \"version\"='7'", 1)
                    .rows().get(0).get(0)).isEqualTo("1");
            assertThat(admin.execute("SELECT COUNT(*) FROM unit_test WHERE name IN "
                    + "('Base24 POS - Compra EMV (DE55)', 'Visa - Tags anidados (77)', "
                    + "'Mastercard - Respuesta y script (71)', "
                    + "'ISO87 - Tag propietario, longitud larga y repetidos')", 1).rows().get(0).get(0))
                    .isEqualTo("4");
        }
    }
}
