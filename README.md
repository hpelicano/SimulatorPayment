# SimulatorPayment — Simulador transaccional Base24

Simulador profesional de escritorio (Java 17 + JavaFX) para probar autorizaciones contra **ACI Base24**:
adquirencia, emisión, validación de mensajería, regresión, stress (TPS), estadísticas, parser multi-formato,
operaciones sobre Guardian vía SSH y comandos Pathway.

## Arquitectura (Maven multi-módulo)

| Módulo | Responsabilidad |
|---|---|
| `sim-common` | Excepciones, `EventBus`, `TransactionEvent`, JSON (Jackson), utilidades (Hex, BCD, EBCDIC, Luhn, redacción de datos sensibles excepto PAN) |
| `sim-config` | `user.json` + `environments.json`, puertos/hosts por entorno, **secretos AES-GCM** con contraseña maestra (PBKDF2), contextos TLS |
| `sim-persistence` | **H2 embebida** + Flyway + HikariCP: catálogo de dialectos, tablas `unit_test`/`regresion`/`stress_test`, tracking request-response, corridas, snapshots y administración |
| `sim-iso-parser` | Motor de mensajería guiado por definiciones JSON: ISO8583 (87/93, ASCII/binario), Base24 POS/ATM, VISA Base I, Mastercard CIS, HPDH, BNET. JSON ⇄ wire, trace, conversión entre formatos |
| `sim-crypto` | **HSM simulado**: PIN block ISO 0/1/3 bajo ZPK, MAC (X9.19, ISO 9797 alg.1, AES-CMAC), CVV/CVV2/iCVV, EMV ARQC/ARPC (session keys EMV CSK), KCV |
| `sim-validator` | Validador de campos de mensajes enviados/recibidos (estructura, reglas, eco de campos, RC esperado, comparación) |
| `sim-transport` | Netty TCP cliente/servidor, framing configurable (2/4 bytes binario, inclusivo, header VISA, 4 dígitos ASCII, sin prefijo), TLS, correlación request/response |
| `sim-acquirer` | Selección de mensajes H2 por tabla/dialecto, alta/actualización, envío TCP/API, validación por `expected_rc` e `ignore_fields`, echo y reversos |
| `sim-issuer` | Emisor simulado en **modo Server** (reglas BIN, monto, PC, terminal, campos → RC, demoras, jitter, sin respuesta, campos a setear; respuesta full-duplex o ruteada por un puerto 2 de salida) y **modo Cliente** (emite transacciones H2 simulando Visa/Mastercard) con log de mensajería exportable |
| `sim-stats` | Aprobadas / rechazadas / timeouts / errores, TPS actual/pico/promedio, percentiles con HdrHistogram, **exportador Prometheus** |
| `sim-regression` | Ejecución de suites desde H2, refresco de campos dinámicos, reportes HTML y JUnit XML, **comparación entre corridas**, **grabación/replay** de tráfico |
| `sim-stress` | Generador de carga modelo abierto: perfiles CONSTANT / RAMP / STEP / SPIKE, máx. en vuelo, **aborto por SLA**, **stress distribuido** (coordinador + agentes HTTP) |
| `sim-ssh` | JSch (fork mwiede): exec, shell interactivo con expect, SFTP, known_hosts |
| `sim-base24` | Doc Builder: transacciones H2, evidencia PTLF/AUDIT por SSH/OSS gtacl y PDF. Pathway con SSH propio y selector de servers, confirmación y auditoría |
| `sim-api-client` | Cliente API Base24 HTTP/1.1 + TLS (mTLS, protocolos/cifrados), API key / Bearer / Basic, reintentos con backoff |
| `sim-ui` | JavaFX + AtlantaFX + Ikonli (tema moderno claro/oscuro, CSS propio): Dashboard, Adquirencia, Emisión (modos Cliente/Server, ruteo full-duplex o por puerto de salida, log exportable, editor visual de reglas), Parser, Formatos (editor visual), Cripto/HSM, Regresión, Grabación/Replay, Stress, Base24, Base de datos, Configuración, Logs. Abre directo en el Dashboard, sin login |
| `sim-app` | Launcher, logback, fat-jar y modo **headless** para CI |

### Estructura del repositorio

```
SimulatorPayment/
├── pom.xml              POM raíz (versiones, dependencyManagement, módulos)
├── build.bat / run.bat  Compilación y ejecución en Windows
├── sim-*/               17 módulos Maven (ver tabla)
├── docs/                prometheus.yml + grafana-dashboard.json
└── skills/              Memoria de contexto: un SKILL.md por módulo (ver sección "Skills")
```

## Requisitos

- JDK 17 o superior
- Maven 3.8+

## Dependencias externas

Las versiones de las dependencias se administran en el `pom.xml` raíz. Maven también debe resolver las dependencias transitivas de estas bibliotecas.

### Dependencias de la aplicación

| Coordenadas Maven | Versión | Uso |
|---|---:|---|
| `com.fasterxml.jackson.core:jackson-databind` | 2.17.2 | Lectura y escritura de JSON |
| `com.fasterxml.jackson.datatype:jackson-datatype-jsr310` | 2.17.2 | Soporte de fechas y horas Java en JSON |
| `com.h2database:h2` | 2.2.224 | Base de datos H2 |
| `org.flywaydb:flyway-core` | 10.17.3 | Migraciones de base de datos |
| `com.zaxxer:HikariCP` | 5.1.0 | Pool de conexiones JDBC |
| `com.github.mwiede:jsch` | 0.2.19 | Conexiones SSH |
| `io.netty:netty-handler` | 4.1.112.Final | Transporte y manejo de conexiones de red |
| `io.netty:netty-codec` | 4.1.112.Final | Codificación y decodificación de mensajes |
| `org.hdrhistogram:HdrHistogram` | 2.2.2 | Histogramas y métricas de rendimiento |
| `org.slf4j:slf4j-api` | 2.0.13 | API de logging |
| `ch.qos.logback:logback-classic` | 1.5.6 | Implementación de logging |
| `org.openjfx:javafx-controls` | 21.0.4 | Controles de interfaz JavaFX |
| `io.github.mkpaz:atlantafx-base` | 2.0.1 | Tema visual AtlantaFX |
| `org.kordamp.ikonli:ikonli-javafx` | 12.3.1 | Integración de iconos con JavaFX |
| `org.kordamp.ikonli:ikonli-material2-pack` | 12.3.1 | Paquete de iconos Material |
| `org.apache.pdfbox:pdfbox` | 3.0.3 | Generación de documentos PDF |

### Dependencias de pruebas

| Coordenadas Maven | Versión | Uso |
|---|---:|---|
| `org.junit.jupiter:junit-jupiter` | 5.10.3 | Pruebas JUnit 5 |
| `org.assertj:assertj-core` | 3.26.3 | Aserciones para pruebas |
| `org.apache.sshd:sshd-core` | 2.13.2 | Servidor SSH embebido para pruebas |
| `org.apache.sshd:sshd-sftp` | 2.13.2 | Pruebas de funcionalidad SFTP |
| `ch.qos.logback:logback-classic` | 1.5.6 | Logging durante las pruebas |

### Plugins Maven

También se requieren los siguientes plugins para compilar, probar y empaquetar el proyecto:

| Plugin | Versión | Uso |
|---|---:|---|
| `org.apache.maven.plugins:maven-enforcer-plugin` | 3.5.0 | Verificación de requisitos de Java y Maven |
| `org.apache.maven.plugins:maven-compiler-plugin` | 3.13.0 | Compilación de código Java |
| `org.apache.maven.plugins:maven-surefire-plugin` | 3.3.1 | Ejecución de pruebas |
| `org.apache.maven.plugins:maven-jar-plugin` | 3.4.2 | Creación de archivos JAR |
| `org.apache.maven.plugins:maven-resources-plugin` | 3.3.1 | Copia de recursos |
| `org.apache.maven.plugins:maven-shade-plugin` | 3.6.0 | Empaquetado del JAR ejecutable con dependencias |
| `org.openjfx:javafx-maven-plugin` | 0.0.8 | Ejecución de la aplicación JavaFX desde Maven |

Los módulos `org.simulator:sim-*` son módulos propios del proyecto y se compilan como parte del reactor Maven; no son dependencias externas que deban descargarse de un repositorio público.

La lista anterior contiene las dependencias declaradas directamente. Para compilar sin conexión también deben estar disponibles sus dependencias transitivas, los plugins Maven y sus propias dependencias. JavaFX puede requerir artefactos nativos específicos del sistema operativo de destino.

## Compilar y ejecutar

### Windows (scripts incluidos)

```bat
build.bat                  :: compila todos los módulos, ejecuta los tests y genera el jar ejecutable
build.bat skiptests        :: compila sin tests
build.bat offline          :: usa sólo el repositorio Maven local (se puede combinar: build.bat skiptests offline)
run.bat                    :: ejecuta la interfaz gráfica
run.bat --headless ...     :: cualquier comando headless (ver abajo)
```

`build.bat` busca Maven en el PATH, `MAVEN_HOME`/`M2_HOME` o el Maven embebido de IntelliJ, y valida Java 17+.
`run.bat` usa `JAVA_HOME` (o `java` del PATH) y acepta `JAVA_OPTS` (por defecto `-Xms256m -Xmx1g`).
El jar resultante es `sim-app\target\simulator-payment.jar`.

### Maven

```bash
mvn clean install                      # compila y ejecuta todos los tests
mvn -pl sim-app javafx:run             # ejecuta la UI en desarrollo
java -jar sim-app/target/simulator-payment.jar
```

Propiedades opcionales: `-Dsimulator.config=<dir>` (por defecto `~/.simulator/config`),
`-Dsimulator.data=<dir>` (H2, por defecto `~/.simulator/data`), `-Dsimulator.logs=<dir>` (por defecto `~/.simulator/logs`).
Si existe la variable de entorno `SIM_MASTER_PASSWORD` se usa para desbloquear los secretos sin diálogo.

### Modo headless (CI / servidores)

```bash
# Regresión: exit code 0 si todo pasa, 1 si hay fallas
java -jar simulator-payment.jar --headless regression --suite SMOKE --env QA --junit report.xml --html report.html
# Emisor simulado standalone
java -jar simulator-payment.jar --headless issuer --port 9100
# Agente de stress distribuido (controlado desde la vista Stress -> Distribuido)
java -jar simulator-payment.jar --headless agent --token S3cr3t0Agente --port 7700 --env QA
# Cualquier comando + métricas Prometheus
java -jar simulator-payment.jar --headless issuer --port 9100 --metrics 9477
```


### Instalador nativo (opcional)

```bash
jpackage --name SimulatorPayment --input sim-app/target --main-jar simulator-payment.jar \
         --main-class org.simulator.app.Launcher --type msi --win-menu --win-shortcut
```

## Primer uso (loopback sin Base24)

1. La aplicación abre **directamente en el Dashboard**, sin login. La **contraseña maestra** (mín. 8 caracteres) se pide
   recién cuando una operación necesita un secreto `ENC(...)`. También se puede desbloquear desde *Configuración → Desbloquear*
   o con la variable `SIM_MASTER_PASSWORD`.
2. **Emisión** → *Modo Server* → ruteo *Full-duplex* → *Iniciar* (puerto 9100 del entorno DEV).
3. **Adquirencia** → canal TCP → *Conectar* → seleccione tabla, dialecto y registro → *Send*.
4. Pruebe montos especiales de las reglas por defecto: `000099999999` → RC 51, `000000099999` → sin respuesta
   (timeout; con *Reverso automático en timeout* activado envía el 0420), `000000055555` → demora 3 s, BIN `999999` → RC 62.
5. Use *Save as* para crear otro mensaje o *Update* para modificar el seleccionado.
6. **Stress** → perfil RAMP de 1 a 200 TPS y observe el dashboard.

## Configuración

- `environments.json`: por entorno (DEV/QA/…): `acquirer` / `issuer` (host, port, framing, dialect, timeouts, TLS),
  `issuerClient` (conexión del modo Cliente de Emisión), `issuerOutbound` (puerto 2 de salida del modo Server) e
  `issuerRouting` (`FULL_DUPLEX` | `SPLIT_LISTEN` | `SPLIT_CONNECT`),
  `api` (baseUrl, paths, auth, TLS), `guardian` (SSH host/port/usuario/clave o key, known_hosts, subvolumen),
  `pathway` (mode `SOCKET` | `SSH`, host, port, pathmon, prompt, verbos permitidos y que requieren confirmación).
- Secretos: en **Configuración → Cifrado de secretos** se genera `ENC(...)` para pegar en `password`, `apiKey`,
  `bearerToken`, `keyStorePassword`, etc. Nunca se guardan claves en texto plano.
- Archivos opcionales en el directorio de configuración que reemplazan/extienden los incluidos:
  `issuer-rules.json`, `templates/*.json`, `formats/*.json` (nuevos formatos de mensajería),
  `guardian-queries.json`, `guardian-abm.json`.

## Mejoras incorporadas

### Adquirencia y catálogo H2

- Los mensajes reutilizables se almacenan en `unit_test`, `regresion`, `stress_test` o tablas estándar creadas
  desde la vista Base de datos. Los dialectos viven en `dialect`.
- Cada registro contiene request JSON, `expected_rc`, `ignore_fields`, estado y metadatos; no usa `validation_json`.
- Adquirencia filtra por tabla y dialecto, muestra JSON formateado y permite `Reload`, `Update`, `Save as` y `Send`.
- La validación controla estructura, MTI de respuesta, campos eco no ignorados y DE39 contra `expected_rc`.
- `audit_log` guarda request/response/status de cada transacción y `system_audit_log` las operaciones del sistema.

### Emisión: modo Cliente y modo Server

- **Modo Cliente**: conexión IP/puerto independiente (`issuerClient`, default `127.0.0.1:9200`) para emitir
  transacciones simulando una marca (Visa, Mastercard, etc.). Reutiliza el flujo de Adquirencia: tablas H2 +
  dialecto, JSON editable, `Reload`/`Update`/`Save as`/`Send`, echo, reverso y validación. Registra con source
  `ISSUER_CLIENT` y no interfiere con el canal de Adquirencia.
- **Modo Server**: escucha en IP/puerto 1 (entrada) y responde con las **reglas de respuesta (JSON)**. Ruteo:
  - *Full-duplex*: la respuesta vuelve por la misma conexión.
  - *Puertos separados: escuchar en puerto 2*: el originante se conecta al puerto 2 para recibir las respuestas.
  - *Puertos separados: conectar al originante*: el simulador se conecta al host/puerto 2 del originante.
- **Log de mensajería** en cada modo: IN/OUT/ERR, canal/puerto, MTI, STAN, PC, RC, nota y JSON con PAN completo y redacción de otros datos sensibles
  (PAN/track/PIN). *Exportar log* genera un `.log` para documentar la sesión de prueba.

### Framing TCP (prefijo de longitud)

El **dialecto** (Formatos) codifica el mensaje ISO; el **framing** sólo agrega el prefijo de longitud en el socket.
Se toma del endpoint en `environments.json` y se puede cambiar por conexión con el selector *Framing* en
Adquirencia (canal TCP), Emisión Cliente y Emisión Server (*Framing P1* entrada / *Framing P2* salida).

| Valor | Prefijo (payload de 300 bytes) |
|---|---|
| `LEN2_BINARY` (default, BASE24) | `01 2C` |
| `LEN2_BINARY_INCLUSIVE` | `01 2E` (incluye los 2 bytes) |
| `LEN2_BINARY_HEADER` (VISA) | `01 2C 00 00` |
| `LEN4_BINARY` | `00 00 01 2C` |
| `LEN4_ASCII` | `"0300"` (máx. 9999) |
| `LEN4_ASCII_INCLUSIVE` | `"0304"` (incluye los 4 dígitos) |
| `NONE` | sin prefijo: cada lectura TCP = 1 mensaje (sólo bajo volumen) |

El campo `framing` de un dialecto en *Formatos* no lo usa el transporte.

### Operación monousuario

- Sin login, usuarios ni roles: la aplicación abre directamente en el Dashboard y todas las vistas y acciones están
  disponibles.
- El nombre del operador que se registra en la auditoría es `username` de `user.json` (*Configuración → Usuario*).
  Si está vacío, se usa el usuario del sistema operativo.
- Doc Builder consulta evidencia remota con confirmación y auditoría. La funcionalidad Guardian ABM fue retirada.
### HSM / criptografía simulada (vista *Cripto/HSM*)

- Claves `zpk`, `mak`, `cvk`, `imkAc` en `environments.json → crypto` como `ENC(...)`, con KCV visible.
- Si se habilita: en requests `DE52 = PIN:1234` se reemplaza por el PIN block cifrado; si DE55 trae 9F26/9F36 se
  calcula el **ARQC**; se agrega MAC en DE64/DE128. En el emisor se verifica ARQC (RC configurable ante falla), se
  genera **ARPC** (tag 91) y MAC de respuesta. Plantilla `emv-purchase` incluida.
- Herramientas: calcular/verificar CVV, PIN block, MAC, ARQC/ARPC y KCV.

### Grabación y replay de tráfico

- *Grabación/Replay* → **Grabar** captura las transacciones del adquirente y las recibidas por el emisor.
- Las grabaciones se guardan **cifradas** con la clave maestra (contienen mensajes en claro).
- **Reproducir** respeta los tiempos originales (x velocidad, 0 = máximo), filtra por origen y opcionalmente regenera
  STAN/fechas/RRN; el reporte compara RC original vs replay (exportable a HTML).

### Comparación de corridas de regresión

*Regresión → Historial*: seleccionar 2 corridas → **Comparar**. Clasifica cada caso como SAME, REGRESSION, FIXED,
RC_CHANGED, FIELDS_CHANGED, ONLY_IN_BASE / ONLY_IN_OTHER (ignora campos dinámicos 7, 11, 12, 13, 15, 17, 37, 38) y
genera reporte HTML.

### Stress distribuido

1. En cada máquina generadora: `run.bat --headless agent --token <secreto> --port 7700`.
2. En *Stress → Distribuido* cargar una línea por agente: `http://host:7700@<secreto>`; **Health** verifica.
3. **Ejecutar distribuido** divide el perfil (TPS) en partes iguales, arranca los agentes a la vez y grafica las
   muestras agregadas (sumas de conteos; percentiles = máximo entre agentes, cota conservadora).

### Métricas Prometheus / Grafana

- Endpoint `http://<host>:9464/metrics` (puerto configurable; toggle en el Dashboard o `metricsEnabled` al iniciar;
  `--metrics <puerto>` en headless).
- Métricas: `simulator_transactions_total{source,outcome}`, `simulator_response_codes_total{source,rc}`,
  `simulator_latency_seconds` (histograma), `simulator_tps_current`, `simulator_approval_rate`,
  `simulator_acquirer_connected`, `simulator_issuer_connections`, `simulator_stress_running`, etc.
- `docs/prometheus.yml` (scrape de ejemplo) y `docs/grafana-dashboard.json` (importar en Grafana).

### Editores visuales

- *Emisión → Formulario*: alta/baja/orden de reglas, condiciones (MTI, PC, BIN, importes, terminal, campos) y acciones
  (RC, secuencia, demora, jitter, sin respuesta, set/remove de campos), sincronizado con el JSON.
- *Formatos*: tabla editable de campos del dialecto (tipo, longitud, encoding, sub-formato, sensible) y encodings
  generales; guarda en `config/formats/<id>.json` y se aplica en caliente.

## Novedades / Changelog

### v1.3 - Diseño moderno
- **Nueva interfaz**: barra lateral oscura con gradiente, navegación agrupada por secciones (Monitoreo, Transacciones, Mensajería, Pruebas, Base24, Sistema) e íconos Material (Ikonli).
- **Contenido en tarjetas**: fondo con gradiente suave, encabezados de vista con línea de acento y paneles blancos con sombra.
- **Dashboard**: KPIs de colores con ícono (aprobadas en verde, rechazadas en naranja, timeouts en ámbar, errores en rojo, TPS en azul, latencias en violeta) y gráficos con paleta propia.
- **Botones con ícono y color por rol**: primarios en índigo, guardar/iniciar en verde, eliminar/detener en rojo. Se asignan automáticamente desde `Ui.button`.
- **Barra de estado oscura** con indicadores de estado: adquirente, emisor, grabación y métricas.
- **Tema claro/oscuro** desde el botón de la barra lateral; se guarda en `user.json`.
- **Barras de herramientas adaptables** (`WrapBox`): pasan a una segunda línea en lugar de truncar los controles en pantallas chicas.
- Hoja de estilos `sim-ui/src/main/resources/org/simulator/ui/simulator.css` sobre AtlantaFX. Dependencias nuevas: `ikonli-javafx` y `ikonli-material2-pack` 12.3.1.

### v1.2
- **Eliminado el módulo de usuarios**:
  - Se quitaron el login, la vista *Usuarios*, los roles y permisos, y la aprobación maker-checker de ABM.
  - Se eliminó el código de backend correspondiente: `persistence.security`, `ApprovalRepository` y `base24.approval.ApprovalService`.
  - La migración Flyway `V3` borra las tablas `app_user` y `approval_request`.
  - Ya no se usan las propiedades `loginRequired` / `allowSelfApproval` de `user.json`; si aparecen, se ignoran.
- **Inicio directo en el Dashboard**: la contraseña maestra se pide de forma diferida, sólo cuando hace falta un secreto `ENC(...)`.
  - Si el usuario la omite, las tareas en segundo plano no vuelven a preguntar durante 15 s.
  - Las acciones explícitas siempre vuelven a preguntar.
- **ABM de Guardian**: se ejecutan directamente, con confirmación.
- **Ventana adaptada a la resolución**: tamaño según el área útil de la pantalla y vistas con scroll.

### v1.1
- **Usuarios, roles y maker-checker** *(retirado en v1.2)*:
  - Login (admin/admin inicial, se pide cambio de contraseña) y roles VIEWER / OPERATOR / APPROVER / ADMIN.
  - Vista *Usuarios*.
  - Los ABM de Guardian requieren aprobación de un segundo usuario (migración Flyway `V2`).
- **sim-crypto (HSM simulado)**:
  - PIN block, MAC, CVV, ARQC/ARPC.
  - Integrado como hooks en el adquirente y en el emisor.
  - Vista *Cripto/HSM*.
- **Grabación/replay de tráfico**: cifrada con la clave maestra; vista *Grabación/Replay*.
- **Comparación de corridas de regresión** con reporte HTML.
- **Stress distribuido**:
  - Comando `--headless agent`, con coordinador en la vista Stress.
  - Endpoints `/health`, `/run`, `/status`, `/stop` protegidos con `X-Agent-Token`.
- **Exportador Prometheus**:
  - Toggle en el Dashboard, `metricsEnabled` en la configuración y `--metrics <puerto>` en headless.
  - `docs/` con scrape y dashboard Grafana.
- **Editores visuales**: reglas del emisor (formulario) y formatos de mensajería (vista *Formatos*).
- **Seguridad en la UI**:
  - Las vistas y acciones se filtran por permiso.
  - La consola SQL es de sólo lectura sin `DB_WRITE`.
  - La barra de estado muestra el usuario, el rol y el indicador REC.
  - *(Todo esto fue retirado en v1.2.)*
- **Scripts Windows**:
  - `build.bat`: detecta Maven/JDK 17; opciones combinables `offline` y `skiptests`.
  - `run.bat`: GUI o `--headless`; variable `JAVA_OPTS`.
- **Calidad**:
  - 72 tests unitarios/integración en verde.
  - Se ajustó el timeout del test TLS (`Base24ApiClientTest`) para evitar fallas intermitentes.
- **Documentación**: carpeta `skills/` con un SKILL.md por módulo.

### v1.0
- Versión inicial:
  - H2 + Flyway.
  - SSH (JSch), Guardian (consultas/ABM) y Pathway.
  - Adquirencia, emisión y validador.
  - Regresión, stress TPS y estadísticas.
  - API Base24 HTTP/1.1 + TLS.
  - Parser multi-formato: ISO8583, Base24, VISA, MC, HPDH, BNET.

## Base24: Doc Builder y Pathway

**Doc Builder** reemplaza Guardian - Consultas y sus subformularios. Se retiraron
Guardian - ABM y XPNET junto con sus funcionalidades dedicadas.

Seleccione una transacción de **TRANSACTION_LOG** (últimas 1000, filas compactas)
para consultar request/response JSON, PAN y terminal. **Reload** actualiza la lista.
La vista tiene dos columnas: a la izquierda, la tabla de transacciones con sus
botones y los editores Request JSON / Response JSON uno debajo del otro; a la
derecha, un formulario único con PAN, terminal, comandos manuales PTLF y AUDIT,
registros remotos, Vista previa, Ejecutar y el comando OSS / gtacl.

- Ingrese el comando completo PTLF, AUDIT o ambos. Los botones compartidos procesan
  únicamente los campos con contenido, primero PTLF y luego AUDIT. Si ambos están
  vacíos, se informa que faltan datos. No se usan plantillas automáticas en esta vista.
- La tabla unificada identifica cada fila como PTLF o AUDIT; también se conservan
  la salida completa, stderr y el estado de cada consulta para el PDF.
- Las consultas usan SSH Guardian al entorno OSS y gtacl; requieren vista previa
  y confirmación. Las plantillas vienen vacías: configurar el comando real del sitio
  o usar entrada manual. No se asume que FUP decodifique por sí solo los registros PTLF.
- Configurar primero `docBuilder.gtaclCommandTemplate`, el launcher OSS verificado del sitio.
  Debe contener `${command}` y puede usar `${gtaclExecutable}`; ambos se sustituyen
  como literales protegidos para shell. No se presume una opción `gtacl -c` universal.
  Los parsers `ptlfParser`/`auditParser`
  admiten RAW (una fila por línea), JSON, DELIMITED, FIXED o REGEX según la salida del sitio.
- **Help FUP / AUDIT** explica los parámetros y muestra ejemplos orientativos.
- **Descargar PDF** combina metadatos, request/response y evidencia PTLF/AUDIT obtenida.
  El documento presenta encabezados modernos, secciones diferenciadas, cada transacción
  PTLF y cada paso del flujo AUDIT con sus procesos y mensajes, conservando el orden
  recibido del extractor remoto, salida completa y errores.
  Las consultas no ejecutadas se identifican como pendientes; se confirma exportación
  incompleta. Cambiar transacción o comandos invalida evidencia anterior.

**Pathway** unifica la consola remota con SSH propio y un combo de servers.
En `pathway`, configure `mode: "SSH"`, `host`, `port: 22`, `username`, `password`
(o clave privada), `server_name` (selección inicial), `servers` (lista de nombres de
procesos PATHMON), `taclPrompt` y `prompt`. La selección inicia `PATHCOM <server>`;
cada server tiene un historial propio. `allowedVerbs` y `confirmVerbs` controlan los
comandos. Las credenciales no se comparten en ejecución con Guardian.

Al abrir configuraciones antiguas, se retira `xpnet` y se migra el Pathway sin lista
de servers a SSH/22, con su antiguo `pathmon` y credenciales Guardian como valores
iniciales; revisar y adaptar esos datos antes de conectar.

**PAN en texto plano:** por requerimiento del operador, los nuevos registros y vistas
conservan el PAN completo. Proteger H2, logs, grabaciones y PDFs como información sensible.
Los PAN históricos ya enmascarados no se pueden recuperar.
El nombre histórico `pan_masked` de H2 y el accesor `panMasked` se conservan por
compatibilidad, pero los registros nuevos almacenan ahí el PAN completo.

## Parser: detalle de campos TLV / EMV

La pestaña **Trace** incluye una segunda tabla a la derecha para desglosar los Data Elements
definidos como `subFormat: "BER_TLV"` (o `"TLV"`) en el formato, como DE55.
Después de **JSON → Hex**, **Hex → JSON** o **Trace**, se selecciona automáticamente
el primer campo TLV; seleccionar otro campo en Trace actualiza el detalle.

El desglose muestra **Tag**, **descripción EMV**, **longitud en bytes**, **valor hexadecimal**,
**ruta de tags anidados** y **offset dentro del contenido del DE** (sin prefijo de longitud).
Los tags construidos se expanden en filas, preservando tags repetidos; los tags desconocidos
se muestran igualmente. Seleccionar un tag permite leer su valor completo debajo de la tabla.
Los errores de contenido TLV se muestran en el panel sin ocultar el Trace del mensaje.
El JSON y la codificación de la transacción no se modifican por esta vista.

En el panel izquierdo, seleccione **Tablas H2**, opcionalmente **Suite / dialect** y una
transacción. Parser consulta las tablas con `REQUEST` o `REQUEST_JSON`, incluidas las
tablas de mensajes creadas por el usuario. Muestra hasta 1000 registros recientes con
filas de altura fija, sin el JSON en la lista. **Reload** actualiza tablas y registros;
el catálogo también se recarga al abrir la vista.
La selección carga automáticamente JSON formateado, wire, Trace y detalle TLV.
Se usa el `dialect` del JSON; si falta, se toma `DIALECT`/`SUITE_ID` solo si es un
formato registrado. Si no hay formato válido, puede elegirse manualmente en **Formato**.
Los mensajes inválidos o enmascarados que no puedan codificarse quedan visibles con
un aviso para su revisión. La carga no envía mensajes ni modifica la transacción en H2.

La migración **V7** incorpora los cuatro ejemplos sintéticos anteriores a **UNIT_TEST**:
compra EMV Base24 POS, Visa con tags anidados en `77`, respuesta Mastercard con script `71/86`
e ISO87 con tag propietario, longitud BER larga y tags repetidos.
Se conservan entre reinicios y no se vuelven a insertar al recargar.
La respuesta Mastercard está deshabilitada para ejecuciones de pruebas, pero disponible
para inspección en Parser. Se mantienen los editores manuales JSON/hex.

## Skills (memoria de contexto para futuras modificaciones)

La carpeta [`skills/`](skills/README.md) documenta cada módulo en `skills/<modulo>/SKILL.md`. Cada archivo incluye:
- Propósito.
- Dependencias.
- Clases clave y API pública.
- Integraciones con otros módulos.
- Reglas e invariantes (seguridad, PCI, compatibilidad).
- Cómo extender el módulo.
- Tests y comando de build.

[`skills/README.md`](skills/README.md) contiene el índice y el mapa de dependencias.

**Flujo recomendado:**
1. Antes de cambiar un módulo, leer su SKILL.md; es el contexto que necesita un desarrollador o un asistente de IA.
2. Hacer el cambio junto con sus tests. Compilar con `mvn -q -B -o -pl <modulo> -am install` o con `build.bat`.
3. Actualizar el SKILL.md si cambió la API, el comportamiento o las reglas.
4. Si se agrega un módulo, crear `skills/<nuevo-modulo>/SKILL.md` y sumarlo al índice.

## Seguridad / PCI

- PAN en texto plano por decisión explícita del operador; ya no se garantiza enmascarado PCI del PAN.
  PIN/DE52 y demás datos sensibles distintos del PAN siguen sujetos a redacción.
- El hex crudo nunca se persiste; en la UI sólo se muestra bajo demanda.
- Consultas Doc Builder y comandos Pathway críticos requieren confirmación explícita (con vista previa del comando); todo queda en la
  tabla de auditoría.
- Parámetros de comandos remotos validados por regex y sin saltos de línea / `;` (anti-inyección).
- SSH con `StrictHostKeyChecking` y known_hosts; TLS 1.2/1.3 con verificación de hostname configurable.

## Consideraciones / supuestos a validar con la instalación real

- El parser es propio (definiciones JSON), no jPOS, para evitar la licencia AGPL. Agregar un formato = un JSON en `formats/`.
- **BNET y HPDH** están modelados como plantillas: ajustar longitudes/encodings según la especificación del adquirente.
- Los comandos históricos de Guardian (`TXNLOG`, `CAF`, `ptlf2json.sh`) son **ejemplos**: adaptar tablas,
  archivos y scripts del sitio en `guardian-queries.json` / `guardian-abm.json`.
- El gateway Pathway por IP/puerto se asume orientado a líneas con prompt; si el sitio usa otro protocolo, implementar
  `PathwayCommandPort`.

## Guía de sim-crypto y configuración TLS

**`sim-crypto` y TLS cumplen funciones diferentes:** `sim-crypto` trabaja sobre los datos de los mensajes de pago; TLS protege la conexión por la que esos mensajes viajan. Activar TLS no requiere habilitar `sim-crypto`, ni viceversa.

### Funcionamiento de sim-crypto

Es un **HSM de software para pruebas**, no una conexión a un HSM físico ni un reemplazo certificado. Utiliza las primitivas criptográficas de Java para estas operaciones:

| Función | Clave | Uso |
|---|---|---|
| PIN block ISO 0, 1 y 3 | `zpk` | Cifrar y descifrar el bloque PIN de DE52 |
| MAC X9.19, ISO9797 alg.1 o AES-CMAC | `mak` | Calcular y verificar la integridad del mensaje |
| CVV, CVV2 e iCVV | `cvk` | Calcular valores de verificación de tarjeta |
| EMV ARQC y ARPC | `imkAc` | Generar y verificar criptogramas EMV |
| KCV | La clave a comprobar | Mostrar un valor de control de la clave |

**Utilizar únicamente claves de laboratorio. Nunca cargar claves productivas en el simulador.**

#### Procesamiento al enviar una transacción

Cuando el HSM de software está habilitado:

- Si DE52 contiene un marcador como `PIN:1234`, se sustituye por el PIN block cifrado con la ZPK. El PAN se obtiene de DE2 o DE35.
- Si está habilitada la generación de ARQC y DE55 contiene los tags `9F26` y `9F36`, se recalcula `9F26` usando la IMK-AC y los datos EMV.
- Si el mensaje ya incluye DE64 o DE128 y el algoritmo MAC no es `NONE`, se recalcula ese campo con la MAK. **No se agrega automáticamente un campo MAC que no exista en el mensaje.**

En el **emisor simulado**, se puede verificar el ARQC, cambiar DE39 al RC configurado si resulta inválido, generar el ARPC en el tag `91` de DE55 y calcular la MAC de respuesta. La verificación MAC también está disponible como herramienta, pero no debe confundirse con un rechazo automático de toda transacción entrante.

#### Configuración de claves y algoritmos

En **Cripto/HSM → Configuración**, cargar las claves, elegir los algoritmos y guardar. La información se almacena dentro del entorno correspondiente en `environments.json`, en el bloque `crypto`.

Ejemplo de estructura: los valores `ENC(...)` siguientes son **marcadores**, no secretos cifrados utilizables. Reemplazarlos por valores cifrados reales generados por la aplicación.

```json
"crypto": {
  "enabled": true,
  "zpk": "ENC(valor-cifrado-de-la-ZPK)",
  "mak": "ENC(valor-cifrado-de-la-MAK)",
  "cvk": "ENC(valor-cifrado-de-la-CVK)",
  "imkAc": "ENC(valor-cifrado-de-la-IMK-AC)",
  "macAlgorithm": "X919",
  "pinBlockFormat": 0,
  "emvSessionKey": "EMV_CSK",
  "emvIadMode": "CVR",
  "generateArqc": true,
  "generateArpc": true,
  "verifyArqc": true,
  "arqcFailureResponseCode": "05"
}
```

Las claves originales son cadenas hexadecimales: por ejemplo, una clave TDES de 16 bytes tiene **32 caracteres hexadecimales**. El tipo y la longitud deben corresponder al algoritmo elegido.

Las opciones EMV deben coincidir con el perfil de pruebas:

- `emvSessionKey`: `NONE` o `EMV_CSK`.
- `emvIadMode`: `CVR`, `FULL` o `NONE`.
- `macAlgorithm`: `NONE`, `X919`, `ISO9797_ALG1` o `AES_CMAC`.
- `pinBlockFormat`: `0`, `1` o `3`.

Elegir una combinación EMV distinta a la del otro extremo puede producir un ARQC inválido aunque las claves sean correctas. Las calculadoras de **Cripto/HSM** permiten probar PIN block, CVV, MAC, ARQC y KCV antes de enviar mensajes.

### Configuración TLS

**No existe un archivo TLS único del proyecto.** La configuración está en `environments.json`, dentro del bloque `tls` de cada conexión: `acquirer`, `issuer`, `issuerClient`, `issuerOutbound` o `api`.

Por defecto, el archivo se encuentra en `%USERPROFILE%\.simulator\config\environments.json`. En la instalación utilizada:

```text
C:\Users\hpelicano\.simulator\config\environments.json
```

Los archivos de certificados se guardan aparte y se referencian por ruta. La aplicación admite **PKCS12 (`.p12`/`.pfx`) y JKS (`.jks`)**.

#### Diferencia entre truststore y keystore

| Archivo | Contenido | Cuándo se necesita |
|---|---|---|
| **Truststore** | Certificados de las autoridades o certificados confiables | Para validar el certificado del servidor remoto |
| **Keystore** | Clave privada propia y cadena de certificados | Para un servidor TLS o un cliente con autenticación mutua, mTLS |

Para un cliente TLS normal, normalmente basta con el truststore, o con las autoridades ya confiables para Java. Para mTLS también se necesita el keystore del cliente, y el servidor remoto debe confiar en ese certificado.

#### Ejemplo: Adquirencia como cliente TLS

Colocar este bloque dentro de `acquirer`, conservando el resto de sus parámetros:

```json
"tls": {
  "enabled": true,
  "protocols": ["TLSv1.2", "TLSv1.3"],
  "cipherSuites": [],
  "trustStorePath": "C:\\SimulatorPayment\\certs\\truststore.p12",
  "trustStorePassword": "ENC(password-cifrado-real)",
  "trustStoreType": "PKCS12",
  "keyStorePath": null,
  "keyStorePassword": null,
  "keyStoreType": "PKCS12",
  "verifyHostname": true,
  "trustAll": false
}
```

En JSON, las barras de las rutas Windows se escriben como `\\`. El valor `ENC(password-cifrado-real)` también es un marcador: generar el valor real en **Configuración → Cifrado de secretos**.

Si el servidor exige **mTLS**, reemplazar los campos del keystore por:

```json
"keyStorePath": "C:\\SimulatorPayment\\certs\\cliente.p12",
"keyStorePassword": "ENC(password-cifrado-real)",
"keyStoreType": "PKCS12"
```

La implementación actual usa la misma contraseña para abrir el keystore y recuperar la clave privada: **ambas contraseñas deben coincidir**.

#### Crear y revisar un truststore con keytool

Si se dispone del certificado de la CA del servidor, se puede importar desde una consola Windows:

```bat
keytool -importcert -alias ca-base24 ^
  -file "C:\SimulatorPayment\certs\ca-base24.cer" ^
  -keystore "C:\SimulatorPayment\certs\truststore.p12" ^
  -storetype PKCS12
```

El comando solicita la contraseña y confirmación. Antes de aceptar, verificar la huella del certificado con quien lo proporcionó. Si se necesitan autoridades intermedias, importarlas también con alias diferentes.

Para revisar el contenido:

```bat
keytool -list -v ^
  -keystore "C:\SimulatorPayment\certs\truststore.p12" ^
  -storetype PKCS12
```

El keystore para mTLS debe contener una **`PrivateKeyEntry`**, no solamente un certificado importado. Generalmente lo proporciona el responsable de certificados del entorno.

#### Particularidades de la implementación actual

| Canal | Comportamiento actual |
|---|---|
| **API HTTPS** | Aplica protocolos y `cipherSuites`. La URL debe comenzar con `https://`; poner `enabled: false` no convierte una URL HTTPS en HTTP. |
| **TCP ISO / Netty** | Aplica TLS si `enabled` es `true` y limita los protocolos configurados. Actualmente no aplica `cipherSuites` ni configura explícitamente la validación del hostname. |
| **Emisor TCP como servidor** | Necesita un keystore con clave privada y certificado. Actualmente no exige certificados de cliente; cargar un truststore no activa por sí solo mTLS obligatorio. |

Por lo tanto, **`verifyHostname: true` no garantiza actualmente validación del nombre del servidor en el canal TCP**, aunque se valide la confianza de su certificado. Para HTTPS, mantener el hostname de la URL acorde al SAN del certificado. La desactivación de hostname en el cliente API utiliza una propiedad global del JDK y no debe usarse como configuración normal.

Mantener **`trustAll: false`**: activarlo deshabilita la validación de confianza del certificado. Si no se indica un truststore y `trustAll` permanece desactivado, Java utiliza su configuración de confianza predeterminada.

Después de guardar la configuración, desconectar y reconectar el canal, o reiniciar la aplicación, para que se construya el nuevo contexto TLS.
