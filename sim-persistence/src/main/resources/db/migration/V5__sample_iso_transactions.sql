-- Sample ISO messages for validating the H2 selectors, editors and execution flows.

INSERT INTO unit_test(suite_id, name, request, expected_rc, validation_json, ignore_fields, enabled) VALUES
('base24-pos', 'Compra POS aprobada',
 '{"dialect":"base24-pos","mti":"0200","fields":{"2":"4761739001010010","3":"000000","4":"000000010000","7":"0927120000","11":"100001","22":"051","25":"00","37":"000001100001","41":"TERM0001","42":"MERCHANT0000001","49":"032"}}',
 '00', '{"name":"Compra aprobada","expectedResponseCode":"00"}', '7,11,12,13,37,38', TRUE),
('base24-atm', 'Extracción ATM aprobada',
 '{"dialect":"base24-atm","mti":"0200","fields":{"2":"4761739001010010","3":"011000","4":"000000005000","7":"0927120100","11":"100002","22":"051","25":"00","37":"000001100002","41":"ATM00001","49":"032"}}',
 '00', '{"name":"Extracción aprobada","expectedResponseCode":"00"}', '7,11,12,13,37,38', TRUE),
('base24-pos', 'Echo de conectividad',
 '{"dialect":"base24-pos","mti":"0800","fields":{"7":"0927120200","11":"100003","70":"301"}}',
 '00', '{"name":"Echo","expectedResponseCode":"00","echoFields":[7,11,70]}', '7,11', TRUE);

INSERT INTO regresion(suite_id, name, request, expected_rc, validation_json, ignore_fields, enabled) VALUES
('base24-regresion', 'Regresión compra POS',
 '{"dialect":"base24-pos","mti":"0200","fields":{"2":"4761739001010010","3":"000000","4":"000000001500","7":"0927121000","11":"200001","22":"051","25":"00","37":"000002200001","41":"TERM0001","42":"MERCHANT0000001","49":"032"}}',
 '00', '{"name":"Regresión compra","expectedResponseCode":"00"}', '7,11,12,13,37,38', TRUE),
('base24-regresion', 'Regresión consulta de saldo ATM',
 '{"dialect":"base24-atm","mti":"0200","fields":{"2":"4761739001010010","3":"311000","4":"000000000000","7":"0927121100","11":"200002","22":"051","25":"00","37":"000002200002","41":"ATM00001","49":"032"}}',
 '00', '{"name":"Regresión saldo","expectedResponseCode":"00"}', '7,11,12,13,37,38', TRUE),
('base24-regresion', 'Regresión echo',
 '{"dialect":"base24-pos","mti":"0800","fields":{"7":"0927121200","11":"200003","70":"301"}}',
 '00', '{"name":"Regresión echo","expectedResponseCode":"00","echoFields":[7,11,70]}', '7,11', TRUE);

INSERT INTO stress_test(suite_id, name, request, expected_rc, validation_json, ignore_fields, enabled) VALUES
('base24-stress', 'Carga compra POS importe bajo',
 '{"dialect":"base24-pos","mti":"0200","fields":{"2":"4761739001010010","3":"000000","4":"000000000100","7":"0927122000","11":"300001","22":"051","25":"00","37":"000003300001","41":"TERM0001","42":"MERCHANT0000001","49":"032"}}',
 '00', '{"name":"Stress compra","expectedResponseCode":"00"}', '7,11,12,13,37,38', TRUE),
('base24-stress', 'Carga consulta de saldo ATM',
 '{"dialect":"base24-atm","mti":"0200","fields":{"2":"4761739001010010","3":"311000","4":"000000000000","7":"0927122100","11":"300002","22":"051","25":"00","37":"000003300002","41":"ATM00001","49":"032"}}',
 '00', '{"name":"Stress saldo","expectedResponseCode":"00"}', '7,11,12,13,37,38', TRUE),
('base24-stress', 'Carga echo',
 '{"dialect":"base24-pos","mti":"0800","fields":{"7":"0927122200","11":"300003","70":"301"}}',
 '00', '{"name":"Stress echo","expectedResponseCode":"00","echoFields":[7,11,70]}', '7,11', TRUE);

INSERT INTO audit_log(request, response, status) VALUES
('{"dialect":"base24-pos","mti":"0200","fields":{"2":"476173******0010","3":"000000","4":"000000010000","7":"0927123000","11":"400001","41":"TERM0001","49":"032"}}',
 '{"dialect":"base24-pos","mti":"0210","fields":{"3":"000000","4":"000000010000","11":"400001","37":"000004400001","38":"A10001","39":"00","41":"TERM0001"}}',
 'APPROVED'),
('{"dialect":"base24-atm","mti":"0200","fields":{"2":"476173******0010","3":"011000","4":"000000005000","7":"0927123100","11":"400002","41":"ATM00001","49":"032"}}',
 '{"dialect":"base24-atm","mti":"0210","fields":{"3":"011000","4":"000000005000","11":"400002","37":"000004400002","39":"51","41":"ATM00001"}}',
 'DECLINED'),
('{"dialect":"base24-pos","mti":"0800","fields":{"7":"0927123200","11":"400003","70":"301"}}',
 '{"dialect":"base24-pos","mti":"0810","fields":{"7":"0927123200","11":"400003","39":"00","70":"301"}}',
 'APPROVED');
