# Skills — memoria de contexto por módulo

Cada carpeta contiene un `SKILL.md` que describe un módulo Maven de SimulatorPayment. Todos siguen la misma estructura:
- Propósito y ubicación.
- Dependencias.
- Clases clave y su API.
- Integraciones con otros módulos.
- Reglas e invariantes.
- Cómo extenderlo.
- Tests y comando de build.

**Antes de modificar un módulo, leer su SKILL.md.** Después de modificarlo, actualizar ese SKILL.md si cambió su API, su comportamiento o sus reglas.

## Mapa de dependencias

```
sim-common ─┬─ sim-config ─┬─ sim-ssh ──────────────┐
            │              ├─ sim-crypto (+iso)     │
            │              └─ sim-api-client (+iso) │
            ├─ sim-persistence ─────────────────────┼─ sim-base24
            ├─ sim-iso-parser ─┬─ sim-validator     │
            │                  └─ sim-transport ─┬─ sim-acquirer (+validator, persistence) ── sim-regression
            │                                    └─ sim-issuer
            ├─ sim-stats
            └─ sim-stress
todos los anteriores ── sim-ui ── sim-app (jar ejecutable + headless)
```

## Índice

| Módulo | Skill | Tema |
|---|---|---|
| sim-common | [SKILL.md](sim-common/SKILL.md) | EventBus, TransactionEvent, JSON, Hex/BCD, Luhn, enmascarado PCI |
| sim-config | [SKILL.md](sim-config/SKILL.md) | user.json / environments.json, secretos ENC(...), TLS |
| sim-persistence | [SKILL.md](sim-persistence/SKILL.md) | H2 + Flyway, repositorios, auditoría, administración local |
| sim-iso-parser | [SKILL.md](sim-iso-parser/SKILL.md) | Dialectos JSON: ISO8583, Base24, VISA, MC, HPDH, BNET |
| sim-crypto | [SKILL.md](sim-crypto/SKILL.md) | HSM simulado: PIN, MAC, CVV, EMV ARQC/ARPC |
| sim-validator | [SKILL.md](sim-validator/SKILL.md) | Validación de campos request/response |
| sim-transport | [SKILL.md](sim-transport/SKILL.md) | Netty TCP, framing, correlación, TLS |
| sim-api-client | [SKILL.md](sim-api-client/SKILL.md) | API Base24 HTTP/1.1 + TLS |
| sim-ssh | [SKILL.md](sim-ssh/SKILL.md) | JSch: exec, shell, SFTP |
| sim-base24 | [SKILL.md](sim-base24/SKILL.md) | Guardian consultas/ABM (directo con confirmación), Pathway |
| sim-acquirer | [SKILL.md](sim-acquirer/SKILL.md) | Plantillas, envío, reversos, hooks |
| sim-issuer | [SKILL.md](sim-issuer/SKILL.md) | Emisor simulado con reglas |
| sim-stats | [SKILL.md](sim-stats/SKILL.md) | Estadísticas y Prometheus |
| sim-regression | [SKILL.md](sim-regression/SKILL.md) | Regresión, comparación de corridas, record & replay |
| sim-stress | [SKILL.md](sim-stress/SKILL.md) | Stress TPS, SLA, stress distribuido |
| sim-ui | [SKILL.md](sim-ui/SKILL.md) | JavaFX, AppContext, vistas, desbloqueo diferido de secretos |
| sim-app | [SKILL.md](sim-app/SKILL.md) | Launcher, fat jar, headless, build.bat / run.bat |

## Convenciones globales
- Java 17. Paquete raíz `org.simulator.<modulo>`. Versión `1.0.0-SNAPSHOT`, gestionada en el `pom.xml` raíz (`dependencyManagement`).
- Un módulo nuevo se agrega en `<modules>` y en `dependencyManagement` del POM raíz. Crear también su `skills/<modulo>/SKILL.md`.
- Build de un módulo:
  - `mvn -q -B -o -pl <modulo> -am install`.
  - Después de tocar el POM raíz, correr antes `mvn -N install`.
- Build completo: `build.bat` (o `mvn clean install`).
- Ejecutar: `run.bat`.
- Seguridad:
  - Nunca loguear ni persistir PAN, tracks o PIN en claro (`Masking`).
  - Secretos siempre como `ENC(...)`.
  - Monousuario, sin login ni roles. Las acciones críticas piden confirmación y quedan auditadas.
- Cada mejora lleva tests unitarios en el módulo y, al integrarse en la UI, se revisa `AppContextIntegrationTest`.
