---
name: sim-validator
description: Validador de campos de mensajería enviada y recibida - chequeo estructural contra el dialecto, reglas declarativas por campo, eco de campos request→response, RC esperado y comparación de mensajes. Usar al cambiar criterios de validación de transacciones o casos de regresión.
---

# sim-validator

- **Ubicación**: `sim-validator/src/main/java/org/simulator/validator`
- **Dependencias**: sim-iso-parser.

## Clases
| Clase | Rol |
|---|---|
| `MessageValidator` | Validaciones sobre mensajes (detalle abajo). |
| `ValidationProfile` | Expectativas del intercambio, serializadas como JSON dentro de cada caso de prueba (detalle abajo). |
| `FieldRule` | Regla declarativa de un campo (tipos abajo). Atributos: `field`, `type`, `value`/`values`, `severity`. |
| `ValidationIssue` | Problema detectado: target (`REQUEST`/`RESPONSE`), campo, regla, esperado, actual, mensaje, severidad (`ERROR`/`WARNING`). |
| `ValidationResult` | Acumulador: `add`, `addAll`, `issues()`, `passed()` (sin ERROR), `errorCount()`. |

**`MessageValidator`**
- `validateStructure(target, msg, dialect)`: campos definidos, tipo (N/A/AN…), largo fijo o máximo, Luhn de DE2.
- `validateRequest(profile, req, dialect)`.
- `validateExchange(profile, req, resp, dialect)`: MTI de respuesta correcto, eco de campos, RC esperado, reglas de respuesta.
- `compare(expected, actual, ignoreFields)`.

**`ValidationProfile`**
- `expectedRc`, `echoFields` (por defecto 2, 3, 4, 11, 37, 41).
- `requestRules` y `responseRules`.
- `expecting(rc)`.

**Tipos de `FieldRule`**: `REQUIRED, ABSENT, EQUALS, NOT_EQUALS, REGEX, IN, LENGTH, NUMERIC, LUHN, RANGE, ECHO, STARTS_WITH`.

## Usos
- **AcquirerService**: valida cada envío. El outcome lleva el `ValidationResult`, y `TransactionOutcome.passed()` combina el RC con la validación.
- **RegressionRunner**: usa el profile guardado en el caso y la opción `ignoreFields` para los campos dinámicos.
- **Parser/Acquirer**: la UI muestra los issues.

## Al modificar
Para un tipo de regla nuevo:
1. Agregar el valor al enum `FieldRule.Type`.
2. Implementarlo en el `switch` de `MessageValidator`.
3. Agregar un test.

Los profiles viejos en H2 son JSON, así que agregar campos es compatible hacia atrás. Renombrar campos rompe los casos de prueba guardados.

## Tests
`MessageValidatorTest`.
Comando: `mvn -q -B -o -pl sim-validator install`.
