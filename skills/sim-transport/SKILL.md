---
name: sim-transport
description: Transporte TCP con Netty para ISO8583 - cliente con correlación request/response y timeouts, servidor para el emisor, framing de longitud configurable (2/4 bytes binario, 4 dígitos ASCII, inclusivo, header VISA o sin prefijo) y TLS. Usar al tocar conexiones de socket, framing o correlación.
---

# sim-transport

- **Ubicación**: `sim-transport/src/main/java/org/simulator/transport`
- **Dependencias**: sim-iso-parser, sim-config, netty-handler, netty-codec.

## Clases
| Clase | Rol |
|---|---|
| `IsoClient` | Cliente asíncrono persistente (detalle abajo). |
| `IsoServer` | Servidor ISO (detalle abajo). |
| `Framing` | Tipos de encabezado de longitud (detalle abajo). `MAX_FRAME` = 65535. `frame(payload)` para tests. |
| `TransportOptions` | host, port, framing, dialect, timeoutMs, connectTimeoutMs, correlationFields, SSLContext. Builders: `of(...)`, `withTimeout`, `withPort`, `withHost`, `withFraming`, `withTls`. |
| `TransportConfigs.from(TcpEndpoint, secrets)` | Convierte la configuración del entorno en `TransportOptions`, incluido el TLS vía `SslContexts`. |
| `Exchange` | request, response, raw bytes de ida y vuelta, `latencyMicros`. |
| `TransportException` | Distingue `timeout` de los demás errores; lo usan las estadísticas y el reverso automático. |
| `NettySupport` | EventLoopGroups compartidos (daemon) y helpers de pipeline. |

**`IsoClient`**
- `connect()`, `send(req[, timeoutMs])` devuelve `CompletableFuture<Exchange>`, `sendOneWay`, `inFlight()`, `close()`.
- Correlación por `correlationFields` del endpoint (por defecto DE11 + DE41).

**`IsoServer`**
- Construcción: `RequestHandler` que devuelve `Reply.of(resp)`, `Reply.none()` (simula no-respuesta) o una respuesta con demora.
- Métodos: `start()`, `port()` (con puerto 0 elige uno libre), `connectionCount()`, `broadcast(msg)` (p. ej. 0800 del emisor).

**Valores de `Framing`** (prefijo de longitud de cada mensaje en el stream TCP; se aplica igual al enviar y al recibir)

| Valor | Prefijo | Ejemplo payload de 300 bytes | Uso típico |
|---|---|---|---|
| `LEN2_BINARY` (default) | 2 bytes big-endian, **excluye** su propio largo | `01 2C` + payload | BASE24 estándar |
| `LEN2_BINARY_INCLUSIVE` | 2 bytes big-endian, **incluye** los 2 bytes | `01 2E` + payload | Hosts que cuentan el header |
| `LEN2_BINARY_HEADER` | 2 bytes de largo + 2 reservados `00 00`; el largo excluye los 4 | `01 2C 00 00` + payload | VISA (BASE I) |
| `LEN4_BINARY` | 4 bytes big-endian, excluye su propio largo | `00 00 01 2C` + payload | Switches con header de 4 bytes |
| `LEN4_ASCII` | 4 dígitos ASCII, excluye su propio largo (máx. 9999) | `"0300"` + payload | Hosts ASCII |
| `LEN4_ASCII_INCLUSIVE` | 4 dígitos ASCII, incluye los 4 dígitos (máx. payload 9995) | `"0304"` + payload | Hosts ASCII que cuentan el header |
| `NONE` | Sin prefijo: cada lectura TCP = 1 mensaje; se escribe el payload tal cual | payload | Sólo bajo volumen / 1 mensaje por segmento; no confiable con mensajes fragmentados o pipelining |

- `MAX_FRAME` = 65535; `frame(payload)` lanza `IllegalArgumentException` si excede el máximo del tipo (ASCII 9999/9995).
- `Framing.parse(string)`: null o vacío → `LEN2_BINARY`; valor desconocido → `IllegalArgumentException`. `label()` da la descripción corta para la UI.
- **Origen del valor**: siempre el `TcpEndpoint.framing` del entorno (`acquirer`, `issuer`, `issuerClient`, `issuerOutbound`), sobrescribible por conexión desde la UI con `TransportOptions.withFraming(f)` (null = no cambia). El campo `framing` de un dialecto (`MessageDialect`/vista Formatos) **no** lo usa el transporte.
- El framing es independiente del dialecto: el dialecto (id en el JSON `"dialect"` o el del endpoint) codifica el mensaje ISO (`MessageService.pack`), y el framing sólo agrega el prefijo de longitud en el pipeline Netty (`NettySupport.addTlsAndFraming`: TLS → frameDecoder → frameEncoder).

## Reglas
- Los futures se completan siempre, aunque sea con un timeout programado en el event loop. Nunca bloquear dentro del event loop.
- Para un framing nuevo:
  1. Agregar el valor (con `label`) al enum e implementar su decoder y el prefijo en `frame`.
  2. Agregarlo a `ConfigService.FRAMINGS` (validación), al tooltip de `sim-ui/FramingChoice`, a `sim-config` y al README.
  3. Agregar su prefijo esperado en `TransportTest.lengthPrefixesPerFraming` (el round-trip ya es `@EnumSource`).

## Tests
`TransportTest` (10 tests: round-trip de cada framing, prefijos por framing, correlación, timeout, TLS con keystore generado por keytool).
Comando: `mvn -q -B -o -pl sim-transport install`.
