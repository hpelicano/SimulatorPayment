---
name: sim-regression
description: Módulo de regresión - ejecuta suites de casos guardados en H2 a través del adquirente, valida y genera reportes HTML/JUnit; compara dos corridas; y graba/reproduce tráfico real (record & replay cifrado). Usar al cambiar regresión, comparación de corridas o replay.
---

# sim-regression

- **Ubicación**: `sim-regression/src/main/java/org/simulator/regression`
- **Dependencias**: sim-acquirer (arrastra transport, validator y persistence).

## Regresión
- **`RegressionRunner(AcquirerService, TestCaseRepository, RunRepository)`**
  - `run(suiteId, suiteName, env, RegressionOptions, progress)` devuelve un `RegressionReport`. `cancel()`.
  - Carga `enabledBySuite` y ejecuta con la concurrencia configurada.
  - Si `refreshDynamicFields` está activo, regenera STAN, fechas y RRN.
  - Valida cada caso con el `ValidationProfile` guardado y persiste `regression_run` / `regression_result`.
- **`RegressionOptions`**: concurrency, refreshDynamicFields (true por defecto), stopOnFirstFailure, delayBetweenMs, ignoreFields, tagFilter.
- **`CaseResult`**: resultado de un caso (name, passed, expectedRc, actualRc, issues, requestJson, responseJson, error, latencia).
- **`RegressionReport`**: `passed()`, `failed()`, `status()`, `toHtml()`, `toJUnitXml()` (para CI).
- **`TestCaseCapture.capture(...)`**: guarda una transacción ejecutada como caso de prueba. La request se guarda sin enmascarar porque se necesita para reenviarla.

## Comparación de corridas (`compare`)
- **`RegressionComparator(RunRepository[, ignored])`**
  - `compare(baseRunId, otherRunId)` devuelve un `ComparisonReport`.
  - Empareja los casos por id; si no hay id, por nombre.
  - Ignora por defecto los campos dinámicos `DEFAULT_IGNORED` = 7, 11, 12, 13, 15, 17, 37, 38.
- **`ComparisonReport`**: `Change {SAME, REGRESSION, FIXED, RC_CHANGED, FIELDS_CHANGED, ONLY_IN_BASE, ONLY_IN_OTHER}`, `count(c)`, `hasRegressions()`, `summary()`, `toHtml()`.

## Record & replay (`replay`)
- **`TrafficRecorder(MessageService[, ignoredSources])`**
  - `start(name, env)`, `stop()` devuelve una `Recording`, `isRecording`, `size`.
  - Se alimenta con `acquirer.addOutcomeListener(recorder::onOutcome)` y con `issuer` vía `record("ISSUER", …)`.
  - `AppContext` ignora las fuentes `REPLAY` y `STRESS`.
- **`Recording`**: name, environment, createdAt, `items` (`RecordedTransaction`: offsetMs, source, mti, requestJson **en claro**, responseRc, status, latencyMicros).
- **`RecordingStore.save(file, rec, encryptor)` / `load(file, decryptor)`**: usar `config::encryptSecret` / `config::secret`. Requiere la configuración desbloqueada.
- **`ReplayEngine(AcquirerService)`**
  - `replay(rec, speed, refreshDynamicFields, sources, progress)` devuelve un `ReplayReport`.
  - `speed`: 1.0 = tiempo real, 2.0 = el doble de rápido, 0 = máximo.
  - Envía con source `REPLAY`. `cancel()`.
- **`ReplayReport`**: items con RC original vs RC del replay, `matches()`, `mismatches()`, `toHtml()`.

## Reglas
Las grabaciones contienen PAN y tracks en claro: **siempre** se guardan cifradas y nunca se loguean.

## Tests
- `RegressionRunnerTest`.
- `ReplayAndCompareTest`: grabación → replay contra emisor loopback y comparación de dos corridas.

Comando: `mvn -q -B -o -pl sim-regression install`.
