---
name: sim-common
description: Utilidades base compartidas por todo SimulatorPayment (excepciones, EventBus, TransactionEvent, JSON Jackson, Hex/BCD/EBCDIC, Luhn y enmascarado PCI). Usar al tocar serialización JSON, eventos de transacción, conversiones de bytes o reglas de enmascarado.
---

# sim-common

## Propósito
Módulo hoja (sin dependencias internas). Todo el resto depende de él directa o transitivamente.
Cualquier cambio aquí impacta a los 16 módulos restantes: mantener compatibilidad binaria.

- **Ubicación**: `sim-common/src/main/java/org/simulator/common`
- **Dependencias**: jackson-databind, jackson-datatype-jsr310.

## Clases clave
| Clase | Rol |
|---|---|
| `SimulatorException` | RuntimeException base del proyecto (usar en lugar de excepciones chequeadas). |
| `event.EventBus` | Pub/sub en memoria, `subscribe(Class<T>, Consumer)` devuelve `Subscription` (cerrable), `publish(Object)`. |
| `event.TransactionEvent` | Record publicado por acquirer/issuer/stress por cada transacción: `source`, `mti`, `processingCode`, `bin`, RC, `outcome`, latencia… `Outcome {APPROVED, DECLINED, TIMEOUT, ERROR}`; `outcomeFor(rc)` mapea RC → outcome (aprobadas: 00, 08, 10, 11, 16, 000, 001). |
| `json.Json` | `ObjectMapper` único y configurado (JSR-310, sin fallar por propiedades desconocidas). `write/read` desde String, Path o InputStream. **Siempre usar este mapper** para mantener el formato consistente. |
| `util.Hex` | `encode`, `decode`, `dump` (hex dump con offsets para la UI). |
| `util.Bcd` | BCD empaquetado, con padding derecho (`encodeRightPadded`) para campos impares. |
| `util.Charsets` | Constantes `ASCII` (ISO-8859-1) y `EBCDIC` (resuelto dinámicamente). |
| `util.Luhn` | Validación / dígito verificador de PAN. |
| `util.Masking` | PAN completo por requerimiento del operador: maskPan conserva el valor; maskTrack preserva PAN pero oculta datos no-PAN; maskFields conserva DE2/DE55 y elimina DE52/DE53. No afirmar enmascarado PCI del PAN |

## Invariantes / reglas
- Nada que se loguee o persista debe salir sin pasar por `Masking.maskFields`.
- `TransactionEvent.source` es un string libre, valores usados: `ACQUIRER`, `ISSUER`, `ISSUER_CLIENT`, `REGRESSION`, `STRESS`, `REPLAY`, `API`.
  Estadísticas, Prometheus y el TrafficRecorder filtran por este valor.

## Tests
`CommonUtilsTest` (Hex, BCD, Luhn, Masking).
`mvn -q -B -o -pl sim-common install`

## Al modificar
- Nuevo campo sensible → agregar en `Masking.maskFields` y test.
- Nuevo campo en `TransactionEvent` → revisar `StatisticsCollector`, `PrometheusExporter`, `AcquirerService`, `IssuerSimulator`, `StressEngine`.
