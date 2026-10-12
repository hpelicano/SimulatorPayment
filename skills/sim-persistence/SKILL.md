---
name: sim-persistence
description: Base de datos embebida H2 (Flyway + HikariCP) con catálogo de dialectos, mensajes reutilizables, corridas, tracking transaccional y administración local. Usar al cambiar el esquema H2 o sus repositorios.
---

# sim-persistence

- **Ubicación**: `sim-persistence/src/main/java/org/simulator/persistence`
- **Dependencias**: sim-common, h2, flyway-core, HikariCP.
- **Datos**: `~/.simulator/data`, configurable con `-Dsimulator.data=<dir>`. Tests usan `Database.inMemory(name)`.

## Esquema (Flyway, `src/main/resources/db/migration`)
| Migración | Tablas |
|---|---|
| `V1__init.sql` | test_suite, test_case, transaction_log, regression_run, regression_result, stress_run, stress_sample, base24_snapshot, audit_log |
| `V2__users_approvals.sql` | app_user, approval_request (histórica) |
| `V3__drop_users_approvals.sql` | Elimina app_user y approval_request (módulo de usuarios retirado en v1.2) |
| `V4__transaction_test_catalog.sql` | dialect, unit_test, regresion, stress_test, audit_log transaccional y system_audit_log; migra datos anteriores |
| `V5__sample_iso_transactions.sql` | Mensajes ISO de ejemplo para validar las tablas |
| `V6__remove_validation_json.sql` | Elimina validation_json de unit_test, regresion y stress_test |
| `V7__parser_tlv_examples.sql` | Persiste los cuatro ejemplos TLV de Parser en unit_test y agrega suites visa-base1/mastercard-cis/iso87-ascii si no existen; sin borrar datos. Respuesta Mastercard disabled para no ejecutarla como request |

**Nunca editar una migración aplicada**: crear `V4__...sql`, `V5__...sql`, etc. El número de versión debe ser incremental.

## Clases clave
| Clase | Rol |
|---|---|
| `Database` | `file(dir)`, `inMemory(name)`, `defaultDir()`, `dataSource()`, `url()`. Aplica las migraciones al abrir. |
| `Jdbc` | Helper sin boilerplate: `query`, `queryOne`, `update`, `insert` (devuelve la clave generada), `batch`. |
| `DbAdminService` | Explora tablas y dialectos, filtra mensajes por suite, crea tablas estándar/dialectos, inserta y actualiza mensajes, además de consola SQL, exportación, backup y restore. |
| `repo.TestCaseRepository` | Casos de regresión asociados a `dialect.id`: `createSuite`, `suiteIdOrCreate`, `save`, `bySuite`, `enabledBySuite`. |
| `repo.TransactionLogRepository` | Log asíncrono con cola y flush por lotes: `append`, `insertNow`, `recent`, `purgeOlderThan`. PAN completo; PIN y otros sensibles redactados. pan_masked/panMasked conservan nombre histórico, no semántica de enmascarado. Registros antiguos enmascarados irrecuperables. Doc Builder consume request_json/response_json, terminal_id y pan_masked |
| `repo.RunRepository` | Corridas de regresión (`startRegression`/`addRegressionResult`/`finishRegression`/`regressionRuns`/`regressionResults`/`regressionRun`) y de stress (`startStress`/`addStressSamples`/`finishStress`). También snapshots Guardian y `audit(user, action, target, detail, ok)`/`auditTrail`. |

## Al modificar
- Nueva tabla/columna → migración nueva + repositorio + test en `PersistenceTest`.
- Las tablas reutilizables tienen `id`, `suite_id`, `name`, `request`, `expected_rc`, `ignore_fields`, `enabled` y timestamps.
- No agregar `validation_json`: las validaciones se derivan de `expected_rc` y `ignore_fields`.
- `audit_log` contiene request/response/status de transacciones; `system_audit_log` conserva las acciones operativas.
- No hay usuarios ni roles: la auditoría (`RunRepository.audit`) registra el nombre del operador de `user.json`.
- `AppContextIntegrationTest` (sim-ui) arranca H2 en memoria con todas las migraciones: correrlo tras cambios de esquema.

## Tests
`PersistenceTest` (4 tests).
Comando: `mvn -q -B -o -pl sim-persistence install`.
