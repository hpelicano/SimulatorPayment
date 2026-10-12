---
name: sim-crypto
description: HSM por software para el simulador (PIN block ISO 0/1/3 bajo ZPK, MAC X9.19 / ISO9797 alg1 / AES-CMAC en DE64/DE128, CVV/CVV2/iCVV, EMV ARQC/ARPC con derivación de claves, KCV). Usar al tocar criptografía de mensajes, claves o validación EMV.
---

# sim-crypto

- **Ubicación**: `sim-crypto/src/main/java/org/simulator/crypto`
- **Dependencias**: sim-common, sim-config (`CryptoConfig`), sim-iso-parser.
- Solo JCE estándar (DES/DESede/AES en ECB sin padding); no usa librerías externas.

## Primitivas (estáticas, sin estado)
| Clase | Funciones |
|---|---|
| `Ciphers` | `des`, `tdes` (claves de 16 o 24 bytes; `key24` expande), `aes`, `xor`, `kcv(key, aes)`, `oddParity`, `parseKey(hex, name)` (valida longitud). |
| `PinBlock` | `clear(format, pin, pan)` para ISO formato 0/1/3, `encrypt(zpk, …)`, `decryptPin(zpk, hex, pan)`, `decodeClear`. |
| `Mac` | `Algorithm {NONE, X919, ISO9797_ALG1, AES_CMAC}`, `compute(alg, key, data)`, `retail` (X9.19), `alg1`, `aesCmac` (RFC 4493), `pad1`/`pad2`. |
| `Cvv` | `compute(cvk, pan, expiry YYMM, serviceCode)`. CVV2 usa service "000" e iCVV usa "999". |
| `Emv` | `deriveIccMasterKey` (opción A), `sessionKey` (EMV CSK), `cardKey(imk, pan, psn, atc, SessionKey)`, `arqcData(tags, IadMode)`, `arqc`, `arpc` (método 1: ARQC XOR ARC), `tags(de55Hex)`. `SessionKey {NONE (pad1), EMV_CSK (pad2)}`, `IadMode {CVR (bytes 3..6 de 9F10), FULL, NONE}`. |

## `CryptoService`
Aplica el HSM a mensajes ISO. Constructor: `(Supplier<CryptoConfig>, UnaryOperator<String> secrets, MessageService)`.
Si `enabled=false`, todos los métodos son no-op.

**`prepareRequest(req)`**, lado adquirente:
- Si DE52 vale `PIN:<pin>`, lo reemplaza por el PIN block cifrado bajo la ZPK (formato `pinBlockFormat`).
- Si DE55 contiene 9F26 y 9F36 y `generateArqc` está activo, recalcula el ARQC con IMK-AC. El PSN sale de 5F34 o, si falta, de DE23.
- Si el mensaje tiene DE64 o DE128, calcula el MAC. Los datos son los bytes del mensaje empaquetado desde el offset del MTI hasta el offset del campo MAC, obtenidos con `messages.trace`.

**`processResponse(req, resp)`**, lado emisor:
- Verifica el ARQC. Si falla y `arqcFailureResponseCode` está configurado, pisa DE39 con ese valor.
- Agrega el tag 91 = ARPC (8 bytes) + ARC (código de respuesta en ASCII-hex).
- Agrega MAC en la respuesta si la request lo traía.

**Herramientas**: `verifyArqc`, `verifyMac`, `decryptPin` y `key(name)` (claves `zpk|mak|cvk|imkAc` descifradas).

## Integración
- `sim-ui/AppContext` crea el servicio y lo engancha así:
  - `acquirer.setRequestInterceptor(crypto::prepareRequest)`.
  - `issuer.setResponseProcessor(crypto::processResponse)`.
- `CryptoView` expone las herramientas de cálculo en la UI.
- La plantilla `emv-purchase.json` (sim-acquirer) sirve de ejemplo con DE52 `PIN:` y DE55.

## Reglas
- Las claves se leen solo vía `secrets` (valores `ENC(...)`); nunca se loguean. Mostrar solo el KCV.
- DE52/DE53 se reemplazan por `[REMOVED]` antes de persistir (ver `Masking`). Las grabaciones de replay guardan el mensaje en claro y por eso se cifran.

## Tests
`CryptoTest` con vectores conocidos:
- CVV 561.
- AES-CMAC según RFC 4493.
- MAC FIPS 113 = 70A30640CC76DD8B.
- PIN block.
- KCV 08D7B4.
- Pipeline EMV + PIN + MAC completo.

Comando: `mvn -q -B -o -pl sim-crypto install`.
