-- Deja la base vacía antes de recargar data.sql en cada prueba.
DELETE FROM SUPPORT_AUDIT;
-- Primero los REVERSAL, que referencian a otros movimientos del ledger.
DELETE FROM LEDGER_ENTRY WHERE related_entry_id IS NOT NULL;
DELETE FROM LEDGER_ENTRY;
DELETE FROM PROVIDER_EVENT;
DELETE FROM PAYMENT;

ALTER SEQUENCE payment_seq        RESTART WITH 100;
ALTER SEQUENCE provider_event_seq RESTART WITH 100;
ALTER SEQUENCE ledger_entry_seq   RESTART WITH 100;
ALTER SEQUENCE support_audit_seq  RESTART WITH 100;
