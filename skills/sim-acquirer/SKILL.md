---
name: sim-acquirer
description: Módulo de Adquirencia - selección de mensajes H2 por tabla/dialecto, alta y actualización, envío TCP/API, validación por expected_rc e ignore_fields, echo y reversos. Usar al cambiar el flujo de transacciones adquirentes.
---

# sim-acquirer

- **Ubicación**: `sim-acquirer/src/main/java/org/simulator/acquirer`
- **Dependencias**: sim-transport, sim-validator, sim-persistence.

## Clases
| Clase | Rol |
|---|---|
| `AcquirerService` | Orquestador (detalle abajo). |
| `TransactionChannel` | Abstracción "enviar request → respuesta". Implementaciones: `IsoClientChannel` (TCP persistente, sobre `IsoClient`) y `sim-ui/ApiChannel` (API HTTP). |
| `TransactionTemplate` | id, name, description, category, dialect, mti, header, `fields` (con placeholders), `variables` (valores por defecto) y `validation` (`ValidationProfile`). |
| `TemplateEngine` | Resuelve los placeholders (lista abajo). |
| `TemplateRepository` | Plantillas built-in (`resources/templates/index.json`) y de usuario (`config/templates/*.json`, que sobrescriben por id). `get`, `all`, `put`, `save`. |
| `SequenceGenerator` | STAN 000001..999999 (vuelve a empezar) thread-safe. RRN = yDDDhh + STAN. |
| `MessageBuilders` | `reversal(original, response, mti, stan, …)` y `networkManagement(dialect, code, …)`. |
| `TransactionOutcome` | request, response, rawRequest, rawResponse, `status` (Outcome), responseCode, latencia, validación, error. `passed()`. |

**`AcquirerService`**
- Constructor: `(MessageService, MessageValidator, EventBus, TransactionLogRepository[, SequenceGenerator])`.
- Canal: `useChannel(TransactionChannel)`, `disconnect`, `isConnected`, `channel()`.
- Envío:
  - `build(template, vars)`.
  - `send(template, vars, source)` y `send(IsoMessage, ValidationProfile, source)`: ambos devuelven `CompletableFuture<TransactionOutcome>`.
  - `echoTest()` (0800/301) y `reverse(outcome, mti)`.
- Configuración: `setAutoReversalOnTimeout`, `setLogPayloads(false)` (se usa en stress para no saturar el log), `setEnvironment`.
- **Hooks**:
  - `setRequestInterceptor(UnaryOperator<IsoMessage>)`: lo usa sim-crypto para PIN, ARQC y MAC.
  - `addOutcomeListener` / `removeOutcomeListener(BiConsumer<TransactionOutcome,String source>)`: lo usa el TrafficRecorder.
- Por cada envío: valida con `MessageValidator`, publica un `TransactionEvent` en el EventBus y registra en `transaction_log` (PAN completo; PIN y otros datos sensibles redactados).

## Vista Adquirencia
- Los combos seleccionan una tabla de mensajes H2 y un `suite_id`/dialecto; la grilla usa filas de altura fija.
- `Mensaje (JSON)` muestra el request formateado y editable.
- Acciones de la fila: `Reload`, `Update`, `Save as` y `Send`.
- `Update` y `Save as` editan tabla (solo alta), suite, nombre, request, `expected_rc`, `ignore_fields` y `enabled`.
- No existe `validation_json`. Se crea un `ValidationProfile` en ejecución con el RC esperado; `ignore_fields`
  elimina campos volátiles de la comparación eco.
- La pestaña Validación muestra la configuración antes del envío y los controles/resultados después de recibir respuesta.

**Placeholders de `TemplateEngine`**
- `${STAN}`, `${RRN}`, `${TRANSMISSION_DATETIME}` (UTC MMDDhhmmss), `${LOCAL_TIME}`, `${LOCAL_DATE}`.
- `${DATE_YYMMDD}`, `${DATETIME_YYMMDDHHMMSS}`, `${EXPIRY_YYMM}`.
- `${RANDOM_N:n}`, `${RANDOM_AN:n}`.
- Cualquier otro nombre se busca en `variables`. Se admite un valor por defecto: `${AMOUNT:000000001000}`.

## Plantillas incluidas (`resources/templates`)
`purchase`, `emv-purchase` (DE52 `PIN:1234` + DE55 con 9F26/9F36 para sim-crypto), `preauth`, `refund`, `withdrawal`, `balance`, `echo`.
Para agregar una plantilla built-in: crear el JSON y registrarlo en `index.json`. El usuario también puede crear plantillas desde la UI.

## Fuentes (`source`)
`ACQUIRER` (UI), `REGRESSION`, `STRESS`, `REPLAY`. Estadísticas y grabación filtran por este valor.

## Tests
`AcquirerServiceTest`: loopback contra `IsoServer`, placeholders, reverso por timeout, validación.
Comando: `mvn -q -B -o -pl sim-acquirer install`.
