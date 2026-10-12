---
name: sim-app
description: Launcher de SimulatorPayment - main de la aplicación, configuración de logback, fat-jar ejecutable (maven-shade) y modo headless para CI/servidores (regression, issuer, agent de stress distribuido, --metrics). Incluye los scripts build.bat / run.bat de la raíz. Usar al cambiar el empaquetado, comandos de línea o scripts.
---

# sim-app

- **Ubicación**: `sim-app/src/main/java/org/simulator/app`
- **Dependencias**: sim-ui, logback-classic. Plugins: maven-shade (fat jar) y javafx-maven-plugin (`mvn -pl sim-app javafx:run`).
- **Artefacto**: `sim-app/target/simulator-payment.jar`, jar ejecutable de unos 20 MB con todas las dependencias.

## Clases
- **`Launcher`**: `main`. Con `--headless` delega en `Headless.run(args sin --headless)`; si no, lanza `SimulatorApp` (JavaFX).
  Existe como clase separada porque el main de un fat jar no puede extender `Application`.
  El aviso "Unsupported JavaFX configuration: unnamed module" es esperado y no afecta el funcionamiento.
- **`Headless`**: comandos sin UI.
  - No hay login: el operador de la auditoría es `username` de `user.json`.
  - `SIM_MASTER_PASSWORD` desbloquea los secretos.
  - `--env X` cambia el entorno activo en memoria.
  - `--metrics <port>` expone Prometheus.

| Comando | Opciones | Resultado |
|---|---|---|
| `regression` | `--suite <n>` (obligatoria), `--concurrency n`, `--junit f`, `--html f` | Exit code 0 si todo pasa, 1 si hay fallas y 2 si hay un error de uso. |
| `issuer` | `--port n` | Emisor simulado hasta Ctrl+C. |
| `agent` | `--token` o `SIM_AGENT_TOKEN` (8+ caracteres), `--port 7700`, `--bind 0.0.0.0`, `--name` | Agente de stress distribuido. Conecta el acquirer por TCP al recibir `/run` y usa la plantilla indicada por el coordinador. |

Para agregar un comando:
1. Agregar un `case` en el `switch (args[0])` de `run()`.
2. Implementar un método estático que devuelva el exit code.
3. Actualizar `usage()`, el javadoc, el README y `run.bat`.

## Recursos
`src/main/resources/logback.xml` escribe en consola, en `${simulator.logs}` (por defecto `~/.simulator/logs`) y en el appender de la UI.

## Propiedades del sistema
- `-Dsimulator.config=<dir>`
- `-Dsimulator.data=<dir>`
- `-Dsimulator.logs=<dir>`

## Scripts (raíz del proyecto)
- **`build.bat [skiptests] [offline]`**:
  - Verifica Java 17+ (`JAVA_HOME` o PATH).
  - Busca Maven en este orden: PATH → `MAVEN_HOME` → `M2_HOME` → Maven embebido de IntelliJ.
  - Ejecuta `mvn -B clean install` (con `-DskipTests` / `-o` según los argumentos) e informa la ruta del jar.
- **`run.bat [args]`**:
  - Verifica que el jar exista; si no, sugiere `build.bat`.
  - Ejecuta `java %JAVA_OPTS% -Dfile.encoding=UTF-8 -jar sim-app\target\simulator-payment.jar %*`. `JAVA_OPTS` por defecto: `-Xms256m -Xmx1g`.
- Los `.bat` deben guardarse en ASCII con CRLF (sin acentos) para que cmd.exe no falle.

## Verificación rápida (smoke test)
1. `run.bat --headless agent --token smoketoken123 --port 7711 --metrics 9477`.
2. Comprobar que `curl http://127.0.0.1:7711/health` y `curl http://127.0.0.1:9477/metrics` responden.
3. Detener el JVM real: `Start-Process` devuelve el PID de un shim, así que buscar el `java.exe` con `simulator-payment` en la línea de comando y detenerlo por PID.

Un JVM que quede vivo bloquea el jar y hace fallar `clean`.
