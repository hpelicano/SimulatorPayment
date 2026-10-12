---
name: sim-stats
description: Estadísticas de transacciones (aprobadas, rechazadas, timeouts, errores, TPS actual/pico/promedio, percentiles de latencia con HdrHistogram, por RC/MTI/origen) y exportador de métricas Prometheus en /metrics. Usar al agregar métricas o cambiar el dashboard/monitoreo.
---

# sim-stats

- **Ubicación**: `sim-stats/src/main/java/org/simulator/stats`
- **Dependencias**: sim-common, HdrHistogram.

## Clases
| Clase | Rol |
|---|---|
| `StatisticsCollector([historySeconds, filter])` | Acumulador de `TransactionEvent` (detalle abajo). |
| `StatsSnapshot` | Record inmutable: total, approved, declined, timeouts, errors, currentTps, peakTps, averageTps, p50/p95/p99/max (ms), mapas por RC/MTI/origen y serie temporal. `approvalRate()`, `errorRate()`. |
| `PrometheusExporter(Supplier<StatsSnapshot>)` | Endpoint de métricas (detalle abajo). |

**`StatisticsCollector`**
- `attach(EventBus)` se suscribe a `TransactionEvent`; `record(e)` también puede llamarse directamente.
- `snapshot()`, `reset()`, `toCsv()`.
- Mantiene una ventana deslizante por segundo para TPS y un histograma de latencias.

**`PrometheusExporter`**
- Ciclo de vida: `attach(bus)`, `start(port)` (usa `com.sun.net.httpserver`; puerto 0 = aleatorio), `stop`, `isRunning`, `port`.
- `scrape()` devuelve el texto en formato v0.0.4.
- `registerGauge(name, help, DoubleSupplier)` agrega gauges dinámicos.

## Métricas expuestas
- `simulator_transactions_total{source,outcome}`
- `simulator_response_codes_total{source,rc}`
- `simulator_transactions_by_mti_total{mti}`
- `simulator_latency_seconds_bucket/sum/count{source}`: histograma, excluye timeouts.
- Gauges del snapshot: `simulator_tps_current`, `simulator_tps_peak`, `simulator_latency_p50_ms/p95_ms/p99_ms`, `simulator_approval_rate`.
- Gauges registrados por `AppContext`: `simulator_acquirer_connected`, `simulator_issuer_connections`, `simulator_stress_running`.

Recursos: `docs/grafana-dashboard.json` (dashboard para importar) y `docs/prometheus.yml` (scrape de ejemplo).
Al agregar métricas, actualizar el dashboard y el README.

## Reglas
- Los números se formatean con `Locale.ROOT`, porque Prometheus requiere punto decimal.
- Escapar los valores de labels (`\`, `"`, salto de línea) con `esc()`.

## Tests
- `StatisticsCollectorTest`.
- `PrometheusExporterTest`: scrape por HTTP, formato y gauges.

Comando: `mvn -q -B -o -pl sim-stats install`.
