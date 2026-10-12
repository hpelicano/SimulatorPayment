---
name: sim-ssh
description: Cliente SSH sobre JSch (fork com.github.mwiede) para el host Guardian/OSS de Base24 - exec remoto, shell interactivo send/expect (TACL/PATHCOM), SFTP y known_hosts. Usar al tocar conectividad SSH o ejecución remota.
---

# sim-ssh

- **Ubicación**: `sim-ssh/src/main/java/org/simulator/ssh`
- **Dependencias**: sim-config, `com.github.mwiede:jsch`. En tests se usa además Apache MINA sshd-core/sshd-sftp.

## Clases
| Clase | Rol |
|---|---|
| `SshClient(SshEndpoint, secrets[, charset])` | Sesión perezosa y reutilizable (`session()`). Ver métodos abajo. Implementa `RemoteExecutor`. |
| `InteractiveShell` | Sobre `ChannelShell`: `sendLine`, `expect(regex, timeout)`, `command(line, promptRegex, timeout)`, `drain`, `transcript`. |
| `RemoteExecutor` | Interfaz "ejecutar comando en Base24". Permite testear sim-base24 sin SSH. |
| `ExecResult` | exitCode, stdout, stderr, duración; `ok()`. |
| `SshException` | Error de conexión o ejecución. |

**Métodos de `SshClient`**
- `testConnection()`.
- `exec(cmd[, timeout])`: ChannelExec; devuelve `ExecResult`.
- `openShell(lineTerminator)`: devuelve un `InteractiveShell`.
- SFTP: `upload`, `download`, `readRemoteFile`.

## Seguridad
- Autenticación por password o por clave privada (con passphrase). Ambas se leen con `secrets`, así que aceptan `ENC(...)`.
- `strictHostKeyChecking` con `knownHostsPath`. Desactivarlo solo en laboratorio.
- Timeouts de conexión y de comando configurables en `SshEndpoint`.

## Integración
`AppContext.ssh()` crea el cliente de forma perezosa a partir de `env().getGuardian()`. Se recrea en `environmentChanged()`.
Lo consumen:
- `GuardianQueryService`.
- `GuardianAbmService`.
- `SshPathwayClient` (sim-base24).

## Tests
`SshClientTest` levanta un servidor SSH embebido (`TestSshServer`, MINA) y prueba exec, shell y SFTP.
Comando: `mvn -q -B -o -pl sim-ssh install`.
