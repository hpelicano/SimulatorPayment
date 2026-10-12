---
name: sim-config
description: Configuración de usuario y de entornos Base24 (user.json / environments.json), puertos/hosts, TLS, SSH, Pathway, API y claves cripto; cifrado de secretos AES-GCM con contraseña maestra. Usar al agregar parámetros de configuración o manejar secretos/TLS.
---

# sim-config

## Propósito
Carga, valida y guarda la configuración; resuelve secretos `ENC(...)`; construye `SSLContext`.

- **Ubicación**: `sim-config/src/main/java/org/simulator/config`
- **Dependencias**: sim-common.
- **Directorio por defecto**: `~/.simulator/config`, que se puede cambiar con `-Dsimulator.config=<dir>`.

## Archivos
| Archivo | Modelo | Contenido |
|---|---|---|
| `user.json` | `model.UserConfig` | usuario, displayName, theme (LIGHT/DARK), language, workspaceDir, logRetentionDays, masterPasswordCheck, **metricsEnabled, metricsPort (9464)**. `loginRequired` y `allowSelfApproval` se retiraron en v1.2; si aparecen en un JSON viejo, se ignoran. |
| `environments.json` | `model.AppConfig` | `activeEnvironment` + lista de `EnvironmentConfig` |

Los defaults se copian desde `src/main/resources/defaults/*.json` en el primer arranque.

## EnvironmentConfig (por entorno DEV/QA/UAT…)
- `acquirer`, `issuer`: `TcpEndpoint` con host, port, framing (`LEN2_BINARY` default, `LEN2_BINARY_INCLUSIVE`, `LEN2_BINARY_HEADER`, `LEN4_BINARY`, `LEN4_ASCII`, `LEN4_ASCII_INCLUSIVE`, `NONE`; detalle en la SKILL sim-transport; `ConfigService.validate()` rechaza otros valores), dialect, timeoutMs, connectTimeoutMs, correlationFields y `TlsConfig`. `issuer` es la entrada (puerto 1) del modo Server de Emisión. El framing configurado es el valor inicial de los selectores *Framing* de la UI, que lo sobrescriben por conexión sin modificar el archivo.
- `issuerClient`: `TcpEndpoint` del modo Cliente de Emisión (default `127.0.0.1:9200`).
- `issuerOutbound`: `TcpEndpoint` del puerto 2 (salida de respuestas) del modo Server (default `127.0.0.1:9101`).
- `issuerRouting`: `FULL_DUPLEX` (default) | `SPLIT_LISTEN` | `SPLIT_CONNECT`; validado en `ConfigService.validate()`. Los tres campos tienen default en Java, por lo que los `environments.json` existentes siguen funcionando.
- `api`: `ApiEndpoint` con baseUrl, transactionPath, authType (`NONE`/`API_KEY`/`BASIC`/`BEARER`), apiKey/usuario/password, timeoutMs, maxRetries, headers y `TlsConfig`.
- `guardian`: `SshEndpoint` con host, port, usuario, password o privateKey, knownHosts, strictHostKeyChecking, timeouts y defaultSubvolume.
- `pathway`: `PathwayEndpoint`, default SSH, host/port (22), username/password y claves/knownHosts
  propios, strictHostKeyChecking, taclPrompt, prompt, lineTerminator, timeoutMs, allowedVerbs/confirmVerbs.
  `server_name` selecciona el proceso inicial y `servers` lista los PATHMON disponibles en UI.
  Cada selección inicia `PATHCOM <server>` en un shell distinto; SOCKET queda compatible.
- `docBuilder`: `DocBuilderConfig`, plantillas remotas PTLF/AUDIT y launcher OSS gtacl.
  Plantillas automáticas vacías por defecto: usar comando manual hasta definir sintaxis del sitio.
  `gtaclCommandTemplate` también vacío: debe configurarse incluso para comandos manuales,
  con `${command}` obligatorio y `${gtaclExecutable}` opcional (valores POSIX-quoted).
  `gtaclExecutable` default gtacl. Parsers RAW/JSON/DELIMITED/FIXED/REGEX con layout del sitio.
- XPNET retirado. Al cargar configuraciones antiguas sin `pathway.servers`, migra Pathway
  a SSH/22 usando `pathmon` como server y copiando las antiguas credenciales Guardian solo
  como punto de partida (después independientes). Guarda environments.json sin xpnet y con docBuilder.
- `crypto`: `CryptoConfig`, consumido por sim-crypto:
  - Claves: enabled, zpk, mak, cvk, imkAc.
  - Algoritmos: macAlgorithm (`NONE|X919|ISO9797_ALG1|AES_CMAC`), pinBlockFormat (0/1/3), emvSessionKey (`NONE|EMV_CSK`), emvIadMode (`CVR|FULL|NONE`).
  - Flags: generateArqc, generateArpc, verifyArqc, arqcFailureResponseCode.

## Clases clave
- `ConfigService`:
  - Ciclo de vida: `load()`, `save()`, `validate()` (lista de errores), `app()`, `user()`, `activeEnvironment()`.
  - Secretos: `unlock(char[])` valida la contraseña maestra contra `masterPasswordCheck`. `secret(v)` descifra `ENC(...)` y devuelve el texto plano tal cual. `encryptSecret(plain)`.
  - Constantes `USER_FILE`, `ENV_FILE`.
- `crypto.SecretCipher`: AES-256-GCM, clave derivada con PBKDF2 a partir de la contraseña maestra. Formato `ENC(base64)`.
- `crypto.SslContexts.create(TlsConfig, secrets)`: keystore/truststore PKCS12/JKS, mTLS y `trustAll` (solo laboratorio).

## Reglas
- **Nunca** guardar secretos en claro. Los campos password, apiKey, bearerToken, keyStorePassword y las claves crypto aceptan `ENC(...)`.
- Leer un secreto siempre vía `config.secret(...)`. Lanza excepción si la configuración está bloqueada y el valor está cifrado.
- Agregar un campo de configuración:
  1. Getter/setter con default en el modelo (Jackson ignora las propiedades desconocidas).
  2. Default en `resources/defaults`.
  3. Validación en `ConfigService.validate()` si aplica.
  4. UI en `sim-ui/ConfigView` y actualización del README.

## Tests
`ConfigServiceTest`: defaults, round-trip de secretos, validación.
Comando: `mvn -q -B -o -pl sim-config install`.
