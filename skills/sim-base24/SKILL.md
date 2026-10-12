---
name: sim-base24
description: Base24 Doc Builder (H2 transaction_log, evidencia PTLF/AUDIT via SSH OSS gtacl y PDF), consola Pathway por server con SSH propio, API y auditoria. Usar al modificar operaciones remotas y documentacion transaccional.
---

# sim-base24

- **Ubicación**: `sim-base24/src/main/java/org/simulator/base24`.
- **Dependencias**: sim-ssh, sim-persistence y PDFBox.
- La UI ya no tiene Guardian - ABM, Guardian - Consultas ni XPNET.
- Eliminados `GuardianAbmService`, `AbmTemplate` y catálogo `guardian-abm.json`.
  No reintroducir operaciones CRUD remotas como parte de Doc Builder.

## Doc Builder
- `DocBuilderPane` en sim-ui consulta `TransactionLogRepository.recent(1000)` tras `flush`.
- Selección local: request_json, response_json, PAN, terminal y metadatos de transaction_log.
- PTLF: comando manual o archivo, desde/hasta HHMMSS, máximo de registros y PAN/terminal.
- AUDIT: comando manual o archivos, processName, options y PAN/terminal.
- `docBuilder` en environments.json contiene las plantillas del sitio y el launcher gtacl.
  `gtaclCommandTemplate` vacío por defecto, obligatorio con `${command}`;
  `${gtaclExecutable}` opcional (default gtacl). La sintaxis/flags debe verificarse
  en el sitio; el launcher sustituye ambos como literales POSIX protegidos.
  No inventar layouts PTLF, comandos de extracción ni una sintaxis AUDIT universal.
  Si las plantillas automáticas están vacías, solo puede ejecutarse comando manual.
- Se ejecuta vía SSH Guardian al entorno OSS; la UI muestra el comando final y confirma
  antes de ejecutar. Valores de formulario no deben interpretarse como comandos OSS.
- Se conserva resultado crudo, stderr, exitCode y filas estructuradas en tablas laterales.
- Cambiar transacción/parámetros/configuración invalida la evidencia anterior; no mezclar
  resultados de transacciones o entornos diferentes en el PDF.
- PDF descargable: metadata, request/response formateados, resultados PTLF/AUDIT, comandos,
  errores y consultas pendientes. PAN en texto plano por requerimiento del operador.
  El resto de datos sensibles sigue la protección del registro transaccional.

## Pathway
- `PathwayCommandPort`: transporte de consola.
- `SshPathwayClient`: SSH propio → TACL → `PATHCOM <server seleccionado>`.
- `SocketPathwayClient`: gateway TCP orientado a líneas (compatibilidad).
- `pathway` configura host/port/username/password y opciones SSH, `servers` (lista de
  procesos PATHMON) y `server_name` (selección inicial).
- `AppContext.pathway(server)` cachea servicios/historiales por proceso, con sesión SSH
  independiente de Guardian; `environmentChanged/closeRemote` cierra shells y sesión.
- `CommandPolicy`: whitelist, confirmación de verbos críticos, sin encadenamiento.
- `PathwayService`: ejecución, historial (200) y auditoría SYSTEM_AUDIT_LOG.
- XPNET ya no tiene pestaña ni servicio/configuración propios.

## Utilidades Guardian conservadas
- `QueryTemplate.Parser`, `OutputParser`, `QueryResult`: parsers JSON/delimitado/fijo/regex
  y resultados estructurados, reutilizables para evidencia remota.
- `GuardianQueryService` y su catálogo histórico quedan como API técnica compatible,
  pero ya no alimentan un selector ni subformularios en Base24.
- `CommandRenderer` y `TemplateCatalog`: utilidades de plantillas; validar parámetros
  y no debilitar la protección frente a saltos de línea/inyección.

## Contexto y límites
- PAN ahora se muestra/persiste en claro; los PAN históricos enmascarados no se recuperan.
- No afirmar que un `FUP DUMP` decodifica o filtra una transacción PTLF por sí mismo.
- Configurar y probar los comandos contra las herramientas reales del sitio.
- Tests de servicios con ejecutores falsos y PDF local, no depender de NonStop real.
