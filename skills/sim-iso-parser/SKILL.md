---
name: sim-iso-parser
description: Motor de mensajería propio guiado por definiciones JSON (dialectos) que convierte JSON ⇄ modelo canónico ⇄ wire para ISO8583 87/93, Base24 POS/ATM, VISA Base I, Mastercard CIS, HPDH, BNET; incluye trace de campos, TLV EMV, tokens Base24 y conversión entre formatos. Usar al agregar o corregir formatos o campos.
---

# sim-iso-parser

- **Ubicación**: `sim-iso-parser/src/main/java/org/simulator/iso`
- **Dependencias**: sim-common.
- Parser propio; no usa jPOS, para evitar su licencia AGPL.

## Modelo
- `IsoMessage`: modelo canónico independiente del formato.
  - Atributos: `dialect`, `header`, `mti` y un `Map<Integer,String>` de campos.
  - Todos los valores son strings; los binarios van en hex.
  - Métodos: `set/get/has/remove/copy`, `responseMti("0200") → "0210"`.
- JSON canónico: `{"dialect":"iso87-ascii","mti":"0200","header":"...","fields":{"2":"4540...","3":"000000",...}}`.

## Dialectos (`src/main/resources/formats/*.json`, listados en `index.json`)
Incluidos: `iso87-ascii`, `iso87-binary`, `iso93-ascii`, `base24-pos`, `base24-atm`, `visa-base1`, `mastercard-cis`, `hpdh`, `bnet`.

- **`MessageDialect`**:
  - Identidad y herencia: `id`, `name`, `description`, `extends` (hereda y sobrescribe campos del padre).
  - Estructura: `header` (`HeaderDef`) y `framing`.
  - Encodings por defecto: `mtiEncoding`, `bitmapEncoding`, `numericEncoding`, `textEncoding`, `lengthEncoding`.
  - `fields` es un mapa `"n" → FieldDef`.
- **`FieldDef`**:
  - Básicos: `number`, `name`, `type` (`N,A,AN,ANS,NS,Z,B`), `lengthType` (`FIXED,LLVAR,LLLVAR,LLLLVAR`), `length` (máximo).
  - Encodings opcionales: `encoding`, `lengthEncoding` (`ASCII,EBCDIC,BCD,BINARY,HEX_ASCII`).
  - Extras: `padNibble`, `subFormat` (`BER_TLV` para DE55; la UI acepta también `TLV`, `BASE24_TOKENS`), `sensitive`.
- **`HeaderDef`**: `length`, `encoding`, `defaultValue`, `lengthOffset`/`lengthSize`/`lengthIncludesHeader` (headers VISA/HPDH con largo embebido), `responseRule` (`ECHO`…).
- **`DialectRegistry`**:
  - `builtIn()` carga los dialectos del classpath.
  - `loadDirectory(dir)` carga los dialectos de usuario (`config/formats/*.json`), que reemplazan a los built-in por id.
  - `register` + `resolveAll()` recalculan la herencia. `get(id)`, `ids()`.

## Fachada `MessageService`
- Registro y caché:
  - `registry()`, `dialect(id)`.
  - `codec(id)`: `IsoCodec` cacheado. **Llamar `clearCache()` después de cambiar dialectos en caliente.**
- Wire:
  - `pack(msg)` / `unpack(dialect, bytes)`.
  - `trace(dialect, bytes)` devuelve `List<FieldTrace>` con offsets y bytes crudos; lo usan ParserView y el cálculo de MAC en sim-crypto.
- JSON:
  - `toJson` / `fromJson` / `toJsonNode` / `fromJsonNode`.
  - `jsonToWire`, `wireToJson`, `hexToJson`.
- Otros: `convert(msg, targetDialect)` convierte entre formatos (misma numeración de campos) y `responseHeader(dialect, reqHeader)`.

## Codecs
- `codec.IsoCodec`: bitmap primario y secundario, MTI, header.
- `codec.FieldCodec`: codifica un campo aplicando los encodings efectivos (los del campo o, si faltan, los del dialecto).
- `codec.Tlv`: BER-TLV para EMV; el valor canónico es hex.
  - `parse(hex)` conserva su API de entradas de primer nivel, usada por Cripto/HSM.
  - `parseDetailed(hex, field)` recorre tags construidos y devuelve `Detail(tag, length, value, offset, path, constructed)` en preorden; conserva tags repetidos.
  - `Detail.description()` identifica tags EMV comunes; los desconocidos se muestran como propietarios/no catalogados.
  - Offsets en bytes relativos al contenido del DE, sin el prefijo ISO de longitud. Las longitudes corresponden al valor en bytes.
  - Acepta longitudes BER definidas cortas/largas y padding EMV `00`/`FF`; rechaza truncamientos, longitudes indefinidas/desbordadas y anidación superior a 32 niveles con campo y offset.
  - ParserView muestra el desglose a la derecha de Trace para cualquier DE configurado con `subFormat: BER_TLV` o `TLV`; no intenta interpretar como TLV campos sin esa configuración.
- `codec.Base24Tokens`: formato de tokens `& 0000200144! Q100002 ...` de Base24 (P-63 / S-126).
- `IsoParseException`: indica el campo y el offset que fallaron.

## Agregar un formato nuevo
1. Crear `formats/<id>.json`, idealmente con `"extends":"iso87-ascii"` y solo las diferencias.
2. Agregarlo a `formats/index.json` si es built-in.
3. Agregar un test de round-trip pack/unpack en `MessageServiceTest`.
4. Alternativa sin recompilar: usar la vista **Formatos** de la UI (`FormatsView`), que guarda en `config/formats/`.

## Supuestos
BNET y HPDH están modelados como plantillas; hay que validar longitudes y encodings contra la especificación del sitio.

## Tests
`MessageServiceTest` (15 tests: todos los dialectos, TLV, tokens, conversión, trace).
Comando: `mvn -q -B -o -pl sim-iso-parser install`.
