---
name: sim-issuer
description: Módulo de Emisión - simulador de emisor en modo Server (escucha requests, p. ej. de Base24, y responde según reglas configurables con RC, demoras, jitter, secuencias, sin respuesta y seteo/borrado de campos; respuesta full-duplex o ruteada por un segundo puerto de salida) y modo Cliente (emite transacciones simulando una marca). Usar al cambiar el comportamiento del emisor simulado o su ruteo.
---

# sim-issuer

- **Ubicación**: `sim-issuer/src/main/java/org/simulator/issuer`
- **Dependencias**: sim-transport.

## Modos de operación (UI `IssuerView`)
| Modo | Implementación | Descripción |
|---|---|---|
| **Cliente** | `AppContext.issuerClient()` (segunda instancia de `AcquirerService`, source `ISSUER_CLIENT`) + `AcquirerView(ctx, true)` | Conexión TCP propia (IP/puerto/framing editables, default `environments.json → issuerClient`) para emitir transacciones simulando Visa/Mastercard/etc. Mismo flujo que Adquirencia: tablas H2 + dialecto, registros, JSON, Reload/Update/Save as/Send, Echo, reverso, pestañas Respuesta/Validación/Hex/Trace. No afecta el canal de Adquirencia. |
| **Server** | `IssuerSimulator.start(in, Routing, out)` | Escucha en IP/puerto 1 (entrada) y responde con las **reglas JSON**. Ruteo `FULL_DUPLEX` (misma conexión), `SPLIT_LISTEN` (escucha en puerto 2; el originante se conecta ahí para recibir respuestas) o `SPLIT_CONNECT` (se conecta al host/puerto 2 del originante). Framing independiente por puerto (*Framing P1* / *Framing P2*). |

Cada modo tiene su **log de mensajería** (`sim-ui/MessageLogPane`): hora, dirección (IN/OUT/ERR), canal/puerto, MTI, STAN, PC, RC, nota y JSON **con PAN completo y otros sensibles redactados**; botón *Exportar log* a archivo `.log` (`emision-cliente-*.log`, `emision-server-*.log`). En Server se registran request (puerto 1), response (puerto 1 o 2), "sin respuesta" y errores de ruteo; en Cliente, request enviado, respuesta/timeout/error y mensajes no solicitados.

## Clases
| Clase | Rol |
|---|---|
| `IssuerSimulator(MessageService, EventBus, IssuerRules)` | Servidor emisor (detalle abajo). |
| `IssuerRuleEngine` | Decide la respuesta (detalle abajo). |
| `IssuerRules` | `defaultResponseCode`, `defaultDelayMs`, `excludeFromResponse`, `generateAuthCode` (DE38) y `rules` (lista ordenada). |
| `ResponseRule` | `name`, `enabled`, `match` y `action` (detalle abajo). |

**`IssuerSimulator`**
- Ciclo de vida: `start(TransportOptions)` (full-duplex) o `start(in, Routing, out)` sobre `IsoServer`, `stop`, `port`, `connections`, `handledCount`.
- Ruteo (`enum Routing { FULL_DUPLEX, SPLIT_LISTEN, SPLIT_CONNECT }`, `Routing.parse`):
  - `FULL_DUPLEX`: `IsoServer.Reply(response, delayMs)` por el mismo socket.
  - `SPLIT_LISTEN`: segundo `IsoServer` en el puerto 2 (los mensajes que entran por ahí se ignoran); la respuesta se envía con `broadcast` a los enlaces conectados.
  - `SPLIT_CONNECT`: `IsoClient` hacia host/puerto 2 del originante (`sendOneWay`); si no conecta al iniciar, reintenta en la primera respuesta.
  - En modo separado el handler devuelve `Reply.none()` y la respuesta sale por un scheduler propio respetando `delayMs`. `broadcast(msg)` también usa el puerto 2.
  - Estado: `routing()`, `outboundDescription()`, `outboundConnections()`, `outboundPort()`, `routeErrorCount()`; `add/removeRouteErrorListener(Consumer<String>)` (sin peer en puerto 2, conexión rechazada…).
- `broadcast(msg)`: mensajes no solicitados, como 0800 sign-on o 0620.
- Hooks:
  - `setResponseProcessor(BinaryOperator<IsoMessage>)`: lo usa sim-crypto para ARQC/ARPC y MAC.
  - `addTrafficObserver` / `removeTrafficObserver(Consumer<Traffic>)`: los usan la grabación y la UI.
  - `setTrafficListener`: listener único que usa `IssuerView`.
  - `Traffic(request, response, rule, delayMs, at, route)`; el constructor de 5 argumentos asume `FULL_DUPLEX`.
- Publica un `TransactionEvent` con source `ISSUER`.

**`IssuerRuleEngine`**
- `decide(req)` devuelve una `Decision(response, delayMs, ruleName)`.
- Usa la **primera** regla habilitada que coincide; si ninguna coincide, aplica el default.
- `defaultRules()` (desde `resources/issuer/default-rules.json`), `load(path)`, `setRules`, `rules()`.

**`ResponseRule`**
- `match`: mti, processingCode, binPrefixes, amountMin/amountMax (comparados contra DE4 **en unidades mínimas**, p. ej. 10000001 = 100000.01), `mti` (admite regex, p. ej. `0[12]00`), terminalId y `fields` (`"!"` = el campo debe estar ausente).
- `action`: responseCode, responseCodeSequence (rotativa), delayMs, jitterMs, noResponse, setFields (acepta `${REQ:n}` y `${RANDOM_N:n}`) y removeFields.

## Reglas por defecto
- Tarjeta bloqueada: BIN 999999 → 62.
- Fondos insuficientes: monto > 100000.00 → 51.
- Timeout forzado: monto 999.99, sin respuesta.
- Respuesta lenta: monto 555.55, 3 s de demora.
- Además: consulta de saldo, reversos (04xx) y gestión de red (08xx).

## Persistencia
- La UI guarda las reglas en `config/issuer-rules.json`, que se carga al iniciar.
- La UI ofrece un editor JSON y uno por formulario (`sim-ui/RulesEditor`), sincronizados.
- Al agregar un atributo a `Match` o a `Action`:
  1. Implementarlo en `IssuerRuleEngine`.
  2. Agregar el control en `RulesEditor` (métodos `show` y `commit`).
  3. Agregar un test.

## Tests
`IssuerSimulatorTest`: loopback con reglas, demoras, no-respuesta y ruteo por puerto de salida (`SPLIT_LISTEN`, `SPLIT_CONNECT`).
Comando: `mvn -q -B -o -pl sim-issuer install`.
