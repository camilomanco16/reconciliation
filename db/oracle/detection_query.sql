-- =============================================================================
-- detection_query.sql
-- Detecta pagos con actividad en las últimas 24 horas que presentan:
--   (a) diferencia entre el monto del pago y su débito neto (DEBIT - REVERSAL), o
--   (b) un estado incompatible con la evidencia del proveedor.
-- Solo lectura: no modifica datos.
-- =============================================================================

WITH
-- 1. Pagos con actividad reciente: se actualizó el pago o llegó un evento
--    del proveedor o se registró un movimiento en el ledger en las últimas 24 h.
recent_payments AS (
    SELECT p.id
    FROM   payment p
    WHERE  p.updated_at >= LOCALTIMESTAMP - INTERVAL '24' HOUR
    UNION
    SELECT e.payment_id
    FROM   provider_event e
    WHERE  e.received_at >= LOCALTIMESTAMP - INTERVAL '24' HOUR
    UNION
    SELECT l.payment_id
    FROM   ledger_entry l
    WHERE  l.created_at >= LOCALTIMESTAMP - INTERVAL '24' HOUR
),

-- 2. Débito neto por pago: los DEBIT suman y los REVERSAL restan.
ledger_net AS (
    SELECT l.payment_id,
           SUM(CASE l.entry_type
                   WHEN 'DEBIT'    THEN  l.amount
                   WHEN 'REVERSAL' THEN -l.amount
               END) AS net_debit
    FROM   ledger_entry l
    GROUP  BY l.payment_id
),

-- 3. Evidencia del proveedor por pago: cuántos eventos APPROVED y DECLINED hay.
provider_evidence AS (
    SELECT e.payment_id,
           COUNT(CASE WHEN e.provider_status = 'APPROVED' THEN 1 END) AS approved_count,
           COUNT(CASE WHEN e.provider_status = 'DECLINED' THEN 1 END) AS declined_count
    FROM   provider_event e
    GROUP  BY e.payment_id
),

-- 4. Vista consolidada: un pago sin movimientos o sin eventos cuenta como 0.
payment_summary AS (
    SELECT p.external_ref,
           p.status,
           p.amount,
           NVL(lnet.net_debit, 0)      AS net_debit,
           NVL(pe.approved_count, 0) AS approved_count,
           NVL(pe.declined_count, 0) AS declined_count,
           p.updated_at
    FROM   payment p
    JOIN   recent_payments   r  ON r.id = p.id
    LEFT   JOIN ledger_net        lnet ON lnet.payment_id = p.id
    LEFT   JOIN provider_evidence pe ON pe.payment_id = p.id
),

-- 5. Clasificación de cada pago según las dos familias de inconsistencia.
classified AS (
    SELECT s.*,
           -- (a) Diferencia de monto:
           --     - un pago CONFIRMED debe tener débito neto igual al monto;
           --     - cualquier débito neto distinto de 0 debe ser exactamente el monto
           --       (detecta duplicados como 2x monto o cobros parciales);
           --     - un pago REJECTED no debe tener débito neto.
           CASE
               WHEN s.status = 'CONFIRMED' AND s.net_debit <> s.amount           THEN 'DEBITO_NETO_DISTINTO_DEL_MONTO'
               WHEN s.net_debit <> 0       AND s.net_debit <> s.amount           THEN 'DEBITO_NETO_DISTINTO_DEL_MONTO'
               WHEN s.status = 'REJECTED'  AND s.net_debit > 0                   THEN 'PAGO_RECHAZADO_CON_DEBITO'
           END AS amount_issue,
           -- (b) Estado incompatible con la evidencia del proveedor:
           CASE
               -- El proveedor envió APPROVED y DECLINED para el mismo pago.
               WHEN s.approved_count > 0 AND s.declined_count > 0                THEN 'EVIDENCIA_CONTRADICTORIA'
               -- Aprobado por el proveedor, pero sigue pendiente o quedó rechazado.
               WHEN s.approved_count > 0 AND s.status IN ('PENDING', 'REJECTED') THEN 'APROBADO_PERO_NO_CONFIRMADO'
               -- Confirmado sin ninguna aprobación del proveedor.
               WHEN s.approved_count = 0 AND s.status = 'CONFIRMED'              THEN 'CONFIRMADO_SIN_APROBACION'
               -- Rechazado por el proveedor, pero sigue pendiente.
               WHEN s.declined_count > 0 AND s.status = 'PENDING'                THEN 'RECHAZADO_PERO_PENDIENTE'
           END AS status_issue
    FROM   payment_summary s
)

-- 6. Resultado: solo los pagos con al menos una inconsistencia.
SELECT c.external_ref,
       c.status,
       c.amount,
       c.net_debit,
       c.net_debit - c.amount AS difference,
       c.approved_count,
       c.declined_count,
       c.amount_issue,
       c.status_issue,
       c.updated_at
FROM   classified c
WHERE  c.amount_issue IS NOT NULL
   OR  c.status_issue IS NOT NULL
ORDER  BY c.updated_at DESC;
