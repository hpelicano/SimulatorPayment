---
name: sim-stress
description: Stress test de volumen (TPS) con modelo de carga abierto - perfiles CONSTANT/RAMP/STEP/SPIKE, límite de transacciones en vuelo, aborto por SLA (p99 / % error), muestras por segundo; y stress distribuido con coordinador y agentes HTTP. Usar al cambiar la generación de carga o el modo distribuido.
---

# sim-stress

- **Ubicación**: `sim-stress/src/main/java/org/simulator/stress`
- **Dependencias**: sim-common, HdrHistogram. **No depende del acquirer**: el destino se inyecta con `StressTarget`.

## Motor
| Clase | Rol |
|---|---|
| `LoadProfile` | Forma de la carga (detalle abajo). |
| `SlaPolicy` | `maxP99Ms`, `maxErrorRatePct`, `graceSec` (segundos consecutivos de violación antes de abortar). `none()`. |
| `StressTarget` | `CompletableFuture<Outcome> fire(long seq)`. En la UI y en los agentes: `seq -> acquirer.send(tpl, vars, "STRESS").thenApply(TransactionOutcome::status)`. |
| `StressEngine` | Generador de modelo abierto (detalle abajo). |
| `StressSample` | Un segundo de la corrida: second, targetTps, sent, completed, approved, declined, timeouts, errors, inFlight, throttled, p50/p99. `errorRatePct()`, `toRow()` (para `stress_sample`). |
| `StressResult` | Totales, achievedTps, percentiles, `status` (COMPLETED / CANCELLED / ABORTED_SLA), abortReason, `toCsv()`. |

**`LoadProfile`**
- `type` (`CONSTANT | RAMP | STEP | SPIKE`).
- Parámetros: targetTps, startTps, durationSec, rampSec, stepTps, stepEverySec, spikeTps, spikeAtSec, spikeDurationSec, maxInFlight.
- `tpsAt(t)`, `constant()`, `ramp()`, `scaled(factor)` (el coordinador lo usa para repartir la carga).

**`StressEngine`**
- `run(profile, sla, target, Listener)` bloquea hasta terminar. `stop()`, `isRunning()`.
- Envía al ritmo del perfil aunque las respuestas se demoren. Si se alcanza `maxInFlight`, cuenta la transacción como `throttled`.
- `Listener.onSample` se llama cada segundo.

## Distribuido (`distributed`)
- **`StressAgentServer(name, token, Function<AgentRequest, StressTarget>)`**
  - `start(bind, port)`: HttpServer con endpoints `GET /health` (sin token), `POST /run`, `GET /status` y `POST /stop`. Todos salvo `/health` requieren el header `X-Agent-Token`.
  - El token debe tener al menos 8 caracteres.
  - Se lanza con `--headless agent --token X --port 7700` (ver `sim-app/Headless`).
- **`AgentRequest`**: la porción del perfil que le toca al agente, más SLA, templateId y variables.
- **`AgentStatus`**: agent, running, samples, result, error.
- **`StressCoordinator(List<Agent>)`**
  - `parse("http://host:port@token\n...")` construye la lista de agentes. `health()`.
  - `run(request, onAggregatedSample)`: divide la carga en partes iguales (`scaled(1/n)`), arranca todos los agentes y consulta `/status` cada segundo.
  - Agrega sumando los conteos y tomando el **máximo** de los percentiles (cota conservadora). `stop()`.

## Tests
- `StressEngineTest`: constante, cancelación, SLA.
- `DistributedStressTest`: dos agentes locales y agregación.

Comando: `mvn -q -B -o -pl sim-stress install`.
