-- Casos de muestra del incidente. Las fechas son relativas al arranque
-- (LOCALTIMESTAMP - INTERVAL) para que siempre caigan en las últimas 24 horas.

-- ---------------------------------------------------------------------------
-- PAYMENT
-- ---------------------------------------------------------------------------
-- PAY-AX91: PENDING, pero el proveedor aprobó y el ledger tiene el débito.
INSERT INTO PAYMENT (id, external_ref, status, amount, updated_at, row_version)
VALUES (1, 'PAY-AX91', 'PENDING', 125000, LOCALTIMESTAMP - INTERVAL '20' HOUR, 0);

-- PAY-BX17: CONFIRMED, reintento generó 2 aprobaciones y 2 débitos de 89000.
INSERT INTO PAYMENT (id, external_ref, status, amount, updated_at, row_version)
VALUES (2, 'PAY-BX17', 'CONFIRMED', 89000, LOCALTIMESTAMP - INTERVAL '14' HOUR, 1);

-- PAY-CQ22: PENDING, evidencia externa contradictoria y sin movimientos en ledger.
INSERT INTO PAYMENT (id, external_ref, status, amount, updated_at, row_version)
VALUES (3, 'PAY-CQ22', 'PENDING', 54000, LOCALTIMESTAMP - INTERVAL '9' HOUR, 0);

-- PAY-DK44: caso sano de control.
INSERT INTO PAYMENT (id, external_ref, status, amount, updated_at, row_version)
VALUES (4, 'PAY-DK44', 'CONFIRMED', 31000, LOCALTIMESTAMP - INTERVAL '3' HOUR, 1);

-- ---------------------------------------------------------------------------
-- PROVIDER_EVENT
-- ---------------------------------------------------------------------------
INSERT INTO PROVIDER_EVENT (id, payment_id, provider_event_id, provider_status, received_at)
VALUES (1, 1, 'EVT-AX91-001', 'APPROVED', LOCALTIMESTAMP - INTERVAL '19' HOUR);

INSERT INTO PROVIDER_EVENT (id, payment_id, provider_event_id, provider_status, received_at)
VALUES (2, 2, 'EVT-BX17-001', 'APPROVED', LOCALTIMESTAMP - INTERVAL '15' HOUR);
INSERT INTO PROVIDER_EVENT (id, payment_id, provider_event_id, provider_status, received_at)
VALUES (3, 2, 'EVT-BX17-002', 'APPROVED', LOCALTIMESTAMP - INTERVAL '870' MINUTE);

INSERT INTO PROVIDER_EVENT (id, payment_id, provider_event_id, provider_status, received_at)
VALUES (4, 3, 'EVT-CQ22-001', 'APPROVED', LOCALTIMESTAMP - INTERVAL '8' HOUR);
INSERT INTO PROVIDER_EVENT (id, payment_id, provider_event_id, provider_status, received_at)
VALUES (5, 3, 'EVT-CQ22-002', 'DECLINED', LOCALTIMESTAMP - INTERVAL '450' MINUTE);

INSERT INTO PROVIDER_EVENT (id, payment_id, provider_event_id, provider_status, received_at)
VALUES (6, 4, 'EVT-DK44-001', 'APPROVED', LOCALTIMESTAMP - INTERVAL '200' MINUTE);

-- ---------------------------------------------------------------------------
-- LEDGER_ENTRY (PAY-CQ22 no tiene movimientos)
-- ---------------------------------------------------------------------------
INSERT INTO LEDGER_ENTRY (id, payment_id, entry_type, amount, related_entry_id, created_at)
VALUES (1, 1, 'DEBIT', 125000, NULL, LOCALTIMESTAMP - INTERVAL '1139' MINUTE);

INSERT INTO LEDGER_ENTRY (id, payment_id, entry_type, amount, related_entry_id, created_at)
VALUES (2, 2, 'DEBIT', 89000, NULL, LOCALTIMESTAMP - INTERVAL '899' MINUTE);
INSERT INTO LEDGER_ENTRY (id, payment_id, entry_type, amount, related_entry_id, created_at)
VALUES (3, 2, 'DEBIT', 89000, NULL, LOCALTIMESTAMP - INTERVAL '869' MINUTE);

INSERT INTO LEDGER_ENTRY (id, payment_id, entry_type, amount, related_entry_id, created_at)
VALUES (4, 4, 'DEBIT', 31000, NULL, LOCALTIMESTAMP - INTERVAL '199' MINUTE);

-- SUPPORT_AUDIT queda vacía: se llenará con las acciones de soporte.
