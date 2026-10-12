-- Preserve the parser's synthetic examples as reusable, persisted transactions.
INSERT INTO dialect(id, description)
SELECT 'visa-base1', 'Visa Base I' WHERE NOT EXISTS (SELECT 1 FROM dialect WHERE id = 'visa-base1');
INSERT INTO dialect(id, description)
SELECT 'mastercard-cis', 'Mastercard CIS' WHERE NOT EXISTS (SELECT 1 FROM dialect WHERE id = 'mastercard-cis');
INSERT INTO dialect(id, description)
SELECT 'iso87-ascii', 'ISO8583 ASCII' WHERE NOT EXISTS (SELECT 1 FROM dialect WHERE id = 'iso87-ascii');

INSERT INTO unit_test(suite_id, name, request, expected_rc, ignore_fields, enabled) VALUES
('base24-pos', 'Base24 POS - Compra EMV (DE55)',
 '{"dialect":"base24-pos","mti":"0200","fields":{"3":"000000","4":"000000010000","7":"1003120000","11":"000001","22":"051","37":"000000000001","41":"TERM0001","49":"032","55":"9F2608A1B2C3D4E5F601029F2701809F100706010A03A0B8009F360200019F3704112233449F02060000000100005F2A0200329A032610039C010095050000000000820218009F1A0200329F34031E0300"}}',
 '00', '7,11,37,38', TRUE),
('visa-base1', 'Visa - Tags anidados (77)',
 '{"dialect":"visa-base1","mti":"0200","fields":{"3":"000000","4":"000000010000","7":"1003120000","11":"000001","22":"051","37":"000000000001","41":"TERM0001","49":"032","55":"77519F2608A1B2C3D4E5F601029F2701809F100706010A03A0B8009F360200019F3704112233449F02060000000100005F2A0200329A032610039C010095050000000000820218009F1A0200329F34031E0300"}}',
 '00', '7,11,37,38', TRUE),
('mastercard-cis', 'Mastercard - Respuesta y script (71)',
 '{"dialect":"mastercard-cis","mti":"0210","fields":{"3":"000000","4":"000000010000","7":"1003120000","11":"000001","22":"051","37":"000000000001","38":"123456","39":"00","41":"TERM0001","49":"032","55":"8A02303091081122334455667788710786058400000000"}}',
 '00', '7,11,37,38', FALSE);

INSERT INTO unit_test(suite_id, name, request, expected_rc, ignore_fields, enabled) VALUES
('iso87-ascii', 'ISO87 - Tag propietario, longitud larga y repetidos',
 CONCAT('{"dialect":"iso87-ascii","mti":"0200","fields":{"3":"000000","4":"000000010000","7":"1003120000","11":"000001","22":"051","37":"000000000001","41":"TERM0001","49":"032","55":"DF018180',
        REPEAT('AB', 128), '9F360200019F36020002"}}'),
 '00', '7,11,37,38', TRUE);
