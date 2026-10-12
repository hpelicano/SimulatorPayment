---
name: sim-ui
description: Interfaz JavaFX + AtlantaFX del simulador y cableado central (AppContext) de todos los servicios. Monousuario, sin login; abre directamente en el Dashboard. Vistas Dashboard, Adquirencia, Emisión, Parser, Formatos, Cripto/HSM, Regresión, Grabación/Replay, Stress, Base24, Base de datos, Configuración y Logs. Usar al agregar pantallas o acciones de UI, al cambiar el wiring de servicios o el manejo de la contraseña maestra.
---

# sim-ui

- **Ubicación**: `sim-ui/src/main/java/org/simulator/ui`
- **Dependencias**: todos los módulos funcionales (acquirer, issuer, stats, regression, stress, base24, api-client, crypto), además de javafx-controls, atlantafx-base y logback.

## Arranque (`SimulatorApp`)
1. `ConfigService.load()` y el tema. Si `validate()` encuentra errores, se muestran.
2. Si existe `SIM_MASTER_PASSWORD`, se hace `config.unlock` sin diálogo. **No se muestra ningún diálogo de contraseña al inicio.**
3. `Database.file` (Flyway aplica las migraciones) y `new AppContext(config, db)`.
4. `ctx.setUnlockPrompt(...)`: registra `MasterPasswordDialog` como desbloqueo **diferido**. Con `onFxThread`, el diálogo corre en el hilo FX; si se llama desde un hilo de fondo, se espera con un `FutureTask`.
5. Si `metricsEnabled`, `ctx.startMetrics()`.
6. `MainWindow`: la primera vista es el **Dashboard**, seleccionada con `selectFirst()`.
7. Tamaño de ventana:
   - Se ajusta al área útil de la pantalla (`Screen.getPrimary().getVisualBounds()`): preferido 1440x900, mínimo 900x600, recortado a la pantalla y centrado.
   - Si la pantalla es más chica que 1440x900, la ventana se abre maximizada.

**No hay login, usuarios, roles ni permisos** (se retiraron en v1.2). No reintroducir `ctx.can(...)` ni `permission()` en las vistas.

## `AppContext`: wiring central (único lugar donde se instancian los servicios)

**Persistencia y mensajería**
- `Jdbc`, `DbAdminService`, `TestCaseRepository`, `RunRepository`, `TransactionLogRepository`.
- `DialectRegistry.builtIn()` + `loadDirectory(config/formats)` → `MessageService`.

**Servicios transaccionales**
- `StatisticsCollector` suscrito al `EventBus`.
- `AcquirerService` e `IssuerSimulator` (reglas desde `config/issuer-rules.json`).
- `issuerClient`: segunda instancia de `AcquirerService` para el modo Cliente de Emisión (canal independiente, mismo interceptor cripto, outcomes al `TrafficRecorder`).
- `CryptoService`, enganchado con `acquirer.setRequestInterceptor(crypto::prepareRequest)` (también en `issuerClient`) e `issuer.setResponseProcessor(crypto::processResponse)`.

**Registro, métricas y otros**
- `TrafficRecorder`: recibe los outcomes del acquirer y el tráfico del issuer (source `ISSUER`).
- `PrometheusExporter`, con gauges de acquirer, issuer y stress.
- `TemplateRepository` (built-in + `config/templates`) y `StressEngine`.

**Servicios remotos**
- Perezosos; se recrean en `environmentChanged()`.
- `ssh()`, `guardianQueries()` (API histórica; sin selector de consultas en UI).
- `pathway(server)`: SSH propio o SOCKET compatible; selección `servers/server_name`, historial por server.
- `api()`.

**Accesores**
- `env()`, `connectAcquirerTcp([Framing])`, `connectAcquirerApi()` (`ApiChannel`), `startIssuer(port)` (usa `issuerRouting`/`issuerOutbound` del entorno), `startIssuer(host, port, [framing,] Routing, outHost, outPort[, outFraming])`, `connectIssuerClient(host, port[, Framing])` (framing null = el del endpoint), `issuerClient()`, `add/removeIssuerClientUnsolicitedListener`, `startMetrics()`, `stopMetrics()`, `close()`.
- `user()`: nombre del operador para la auditoría. Toma `username` de `user.json`; si está vacío, usa `user.name` del sistema operativo.

### Secretos y desbloqueo diferido
- `secret(v)`: si `v` es `ENC(...)` y la configuración está bloqueada, llama a `unlock(false)` (pide la contraseña) y después `config.secret(v)`, que lanza "locked" si sigue bloqueada.
- `ensureUnlocked()`: es `unlock(true)`. Lo usan las acciones explícitas del usuario: cifrar en ConfigView, guardar/abrir grabaciones en ReplayView y cifrar claves en CryptoView. Siempre muestra el diálogo si la configuración sigue bloqueada.
- Reglas de `unlock`:
  - Un único diálogo a la vez (`ReentrantLock`).
  - El hilo FX sólo hace `tryLock`, **nunca bloquea**: un hilo de fondo puede estar esperando que ese mismo hilo FX muestre el diálogo.
  - Si el usuario omite el diálogo, los pedidos implícitos no vuelven a preguntar durante `UNLOCK_RETRY_MS` (15 s). Así se evita una tormenta de diálogos, por ejemplo durante un stress.
- Si se agrega un nuevo uso de secretos, pasar por `ctx::secret`, **no** llamar a `config().secret` directamente.

## Vistas (`view/*`, todas implementan `View`)
- `View` define `title()`, `node()`, `onShow()` y `dispose()`.
- `MainWindow` muestra todas las vistas, cada una envuelta en un `ScrollPane` (`scrollable`, cacheado por vista).
- La barra de estado (`status-bar`) muestra chips (`status-chip` + `ok`/`off`/`rec`): usuario, entorno, adquirente, emisor, grabación y TPS/métricas. El entorno y el estado de los secretos se ven en el `env-badge` de la barra lateral.

| Vista | Contenido |
|---|---|
| DashboardView | Tarjetas Total, Aprobadas, Rechazadas, Timeouts, Errores y TPS actual; gráficos, CSV, toggle Prometheus (vista inicial). La sección Resultados muestra debajo del gráfico circular una tabla compacta con % Aprob., TPS pico/promedio y P50/P95/P99/Máx ms, actualizada con el mismo snapshot que las tarjetas |
| AcquirerView | Canal TCP/API, plantillas, envío, echo, reverso, guardar como caso |
| IssuerView + `RulesEditor` | Toggle **Modo Cliente / Modo Server**. Cliente: `AcquirerView(ctx, true)` (IP/puerto propios, flujo H2 de Adquirencia) + log. Server: IP/puerto 1 de entrada, ruteo (full-duplex, puerto 2 escuchando o conectando al originante), start/stop, reglas por JSON **y formulario** (sincronizadas al cambiar de pestaña), pestañas Tráfico en vivo / Log de mensajes |
| MessageLogPane | Componente de log de mensajería (IN/OUT/ERR, canal, MTI, STAN, PC, RC, nota, JSON con PAN completo y otros sensibles redactados), thread-safe, máx. 5000 entradas, *Exportar log* a `.log` |
| `util/FramingChoice` | `ChoiceBox<Framing>` con etiqueta `NOMBRE - descripción` y tooltip explicativo, inicializado con el framing del endpoint. Usado en Adquirencia (deshabilitado con canal API), Emisión Cliente y Emisión Server (*Framing P1* entrada, *Framing P2* salida, habilitado sólo con ruteo de puertos separados) |
| ParserView | JSON ⇄ wire/hex, trace, conversión de dialectos; segunda tabla TLV/EMV a la derecha de Trace (tag, descripción, longitud, valor hex, ruta anidada y offset relativo al DE), valor completo al seleccionar un tag. Selecciona automáticamente el primer DE TLV; al seleccionar otro DE actualiza o limpia el detalle. Usa el valor canónico y el `subFormat` del dialecto (`BER_TLV`/`TLV`). Errores TLV visibles en el panel sin perder Trace |
| FormatsView | Editor de tabla de `FieldDef`. Guarda `config/formats/<id>.json` y luego ejecuta `register` + `resolveAll` + `clearCache` |
| CryptoView | Configuración de claves con KCV y herramientas CVV/PIN/MAC/ARQC |
| RegressionView | Suites/casos, ejecución, resultados, historial y **Comparar 2 corridas** |
| ReplayView | Grabar/detener, guardar/abrir cifrado (.simrec), replay con velocidad y orígenes, reporte HTML |
| StressView | Perfiles, gráficos TPS/latencia, SLA, CSV y sección **Distribuido** (Health / Ejecutar) |
| Base24View / DocBuilderPane | Doc Builder desde TRANSACTION_LOG: request/response, PAN y terminal; consultas PTLF/AUDIT (manual prioritario o plantillas docBuilder), tablas laterales, Help FUP/AUDIT y PDF. Pathway con selector de servers y SSH propio; API y auditoría. Sin ABM ni XPNET. Evidencia se invalida al cambiar selección/parámetros/configuración; consultas y export PDF en Ui.async |
| DatabaseView | Consola SQL (confirma DROP/DELETE/TRUNCATE), export, backup, restore, purga. Listado oculta AUDIT_LOG, SYSTEM_AUDIT_LOG, BASE24_SNAPSHOT y flyway_schema_history (sin eliminarlas ni restringir SQL). Grilla y log con filas fijas de 24 px; celdas de la grilla sin wrap, saltos de línea/tabulados visibles como espacios sin alterar datos/exportación |
| ConfigView | user.json (usuario, tema, retención, métricas), entornos en JSON, desbloqueo y cifrado de secretos |
| LogsView | Logs en vivo (`UiLogAppender`) |

## Diseño visual (v1.3)
Parser carga transacciones desde H2: panel izquierdo con tabla, suite opcional `(Todas)`,
registros de altura fija y **Reload**. Usa `DbAdminService.messageTables/tableRows`
(REQUEST/REQUEST_JSON, incluidas tablas personalizadas), consulta asíncrona y máximo 1000 filas.
`onShow` recarga el catálogo; tokens de versión evitan resultados asíncronos obsoletos.
Seleccionar un registro carga JSON, wire y Trace/TLV; vacía detalles anteriores.
Usa dialect del JSON o DIALECT/SUITE_ID si es un formato registrado, nunca interpreta
grupos como base24-regresion como codecs. Sin formato válido se permite selección manual;
errores de JSON/wire dejan JSON visible y aviso. Lectura local sin envío ni actualización.
Los cuatro ejemplos TLV se persisten por V7 en UNIT_TEST (respuesta Mastercard disabled),
ya no por un selector en memoria. `util.ParserExamples` queda solo en tests como fixture.

- **Hoja de estilos**: `src/main/resources/org/simulator/ui/simulator.css` (`Icons.STYLESHEET`). `SimulatorApp` la agrega a la escena, por encima del user-agent AtlantaFX Primer.
  - Las variables `-sim-*` se definen en `.root`; `.root.theme-dark` las redefine para el tema oscuro.
  - `MainWindow.applyTheme` cambia el user-agent (PrimerLight/PrimerDark) y la clase `theme-dark` del root.
- **Layout de `MainWindow`**:
  - Barra lateral `sidebar`: brand, `env-badge` (clase `locked` si los secretos están bloqueados), `nav-section` y `nav-item` (ToggleButton; no se puede deseleccionar) y botón de tema.
  - Contenido `content-area` con gradiente.
  - La sección y el ícono de cada vista se definen en `section(View)` / `icon(View)` por nombre de clase. **Al agregar una vista, sumarla ahí.**
- **Decoración automática** (`MainWindow.decorate`) de cada vista `BorderPane`:
  - `top` recibe `view-header`.
  - `center`/`left`/`right`/`bottom` reciben `card`; TextInputControl, TableView y ListView reciben `card-plain`; TabPane recibe `card floating`.
  - También se agregan márgenes si no los hay.
  - Para excluir un nodo, darle la clase `no-card`. El Dashboard lo usa y arma sus propias tarjetas.
- **Íconos** (`util.Icons`, Ikonli Material2 Outlined):
  - `Icons.of(ikon, size)`.
  - `Icons.decorate(button)`, que llama `Ui.button`, elige ícono y rol por el texto del botón: `accent` (Ejecutar/Enviar/Convertir...), `success` (Guardar/Iniciar...), `danger` (Eliminar/Detener...).
  - Los textos `+ - ↑ ↓` se convierten en botones sólo-ícono.
  - **Crear botones siempre con `Ui.button`.** Para un caso nuevo, agregar la regla en `Icons.decorate`.
  - Nombres válidos: enums `Material2OutlinedAL` (A-L) y `Material2OutlinedMZ` (M-Z). No existen DARK_MODE, BOLT, TERMINAL, KEY, DOWNLOAD, etc.; verificar antes de usar uno.
- **Toolbars**: `Ui.bar` devuelve un `WrapBox` (se comporta como un HBox en el que `Ui.spacer()` y los hgrow=ALWAYS absorben el espacio libre, pero pasa a otra línea en lugar de truncar).
- **Clases útiles**: `section-title` (vía `Ui.title`), `code-area` (vía `Ui.code`), `card`, `card-title`, `kpi` + `kpi-ok|kpi-decl|kpi-timeout|kpi-error|kpi-rate|kpi-tps|kpi-lat|kpi-total`.
- **Diálogos**: usar `Ui.styleDialog(pane)` después de definir los ButtonTypes; agrega la hoja de estilos y marca el botón por defecto como `accent`.
- **No usar `setStyle` con colores fijos**: usar las variables `-sim-*` o `-color-*` de AtlantaFX para que funcione el tema oscuro.

## Helpers `util.Ui`
- **Tareas y diálogos**:
  - `async(busy, Callable, onSuccess)`: ejecuta fuera del hilo FX y muestra los errores.
  - `run`, `error`, `info`, `confirm`, `prompt`.
  - `error`, `info` y `confirm` usan `fitToScreen`: Label con ajuste de línea dentro de un ScrollPane, limitado al 60% de la pantalla.
- **Controles**: `button` (con ícono y rol automáticos), `code`, `field`, `bar` (WrapBox), `spacer`, `form(label, control, ...)`, `title`.
- **Tablas**: `column(title, width, getter)`, `fillRows`, `fillMaps`.
- **Archivos**: `saveText`, `chooseOpen`, `chooseSave`, `window`.
- **Formato**: `pretty`, `fmt`, `root(ex)`.

## Convenciones
- Nunca bloquear el hilo FX: usar `Ui.async`.
- Las consultas manuales Doc Builder y operaciones remotas críticas (Pathway críticos), DROP/DELETE y restore piden `Ui.confirm` con vista previa; la ejecución remota se audita.
- No fijar tamaños mínimos grandes ni `setPrefSize` absolutos que asuman resoluciones altas. El `ScrollPane` de `MainWindow` resuelve el desborde.
- Una vista nueva se crea en `view/` y se agrega a la lista de `MainWindow`. El orden de esa lista es el de la navegación, y el primero es la vista inicial.
- Un servicio nuevo se instancia **solo** en `AppContext` y se expone con un accesor.

## Tests
`AppContextIntegrationTest`:
- Loopback emisor ⇄ adquirente, regresión, stress corto y H2 en memoria, sin UI. Verifica además que no existan las tablas `APP_USER` / `APPROVAL_REQUEST`.
- Desbloqueo diferido: sin diálogo para valores planos, reintento con cooldown, `ensureUnlocked` forzado.

Comando: `mvn -q -B -o -pl sim-ui install`.
