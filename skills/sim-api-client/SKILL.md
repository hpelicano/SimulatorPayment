---
name: sim-api-client
description: Cliente de la API transaccional Base24 sobre HTTP/1.1 con TLS/mTLS (protocolos y cifrados configurables), autenticación API key / Basic / Bearer, reintentos con backoff y timeouts. Usar al cambiar la conexión API con Base24.
---

# sim-api-client

- **Ubicación**: `sim-api-client/src/main/java/org/simulator/api`
- **Dependencias**: sim-iso-parser, sim-config. Usa `java.net.http.HttpClient`, forzado a `HTTP_1_1`.

## Clases
- **`Base24ApiClient(ApiEndpoint, secrets, MessageService)`**
  - `send(IsoMessage)`: POST del JSON canónico a `baseUrl + transactionPath`. Devuelve `CompletableFuture<ApiResponse>`; la respuesta JSON se parsea a `IsoMessage`.
  - `call(method, path, body)`: llamadas genéricas.
  - `isTimeout(Throwable)`: distingue un timeout de otros errores.
  - `describe()`: descripción del cliente.
  - Autenticación según `authType`: `API_KEY` (header `apiKeyHeader`), `BASIC` o `BEARER`. Los secretos se resuelven con `secrets`.
  - Reintentos (`maxRetries`) con backoff solo ante 5xx, 429 o IOException. No reintenta otros 4xx ni `HttpTimeoutException`.
  - TLS mediante `SslContexts.create(tls)`, respetando `protocols` y `cipherSuites`.
- **`ApiResponse`**: status HTTP, body, `IsoMessage response`, latencia.

## Integración
`sim-ui/ApiChannel` adapta el cliente a `TransactionChannel` del acquirer (lo conecta `AppContext.connectAcquirerApi()`). Así la misma plantilla puede enviarse por TCP o por API.

## Supuestos
El contrato JSON exacto de la API Base24 del sitio puede diferir del canónico. Si cambia, adaptar la serialización en `send` y el parseo de la respuesta.

## Tests
`Base24ApiClientTest`:
- HttpServer local con auth, 401, reintento ante 503 y timeout.
- HttpsServer con keystore generado por keytool.
- El test TLS usa un timeout de 8 s porque el primer handshake en una JVM fría es lento.

Comando: `mvn -q -B -o -pl sim-api-client install`.
