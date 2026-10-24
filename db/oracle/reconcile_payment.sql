-- =============================================================================
-- reconcile_payment.sql
-- Procedimiento de conciliación de un pago. Aplica la misma tabla de decisión
-- ("tabla de desiciones .xlsx") que ReconciliationService en Java, con las
-- mismas reglas, acciones de auditoría y motivos.
--
-- Parámetros:
--   p_external_ref  IN   referencia externa del pago.
--   p_result        OUT  CONSISTENTE | CORREGIDO | REVISION_MANUAL | NO_ENCONTRADO
--   p_reason        OUT  motivo corto de la decisión.
--
-- CUÁNDO MODIFICA DATOS (en cualquier otro caso es solo lectura):
--   Regla 2  Solo APPROVED, neto = monto, PENDING
--            -> UPDATE PAYMENT a CONFIRMED + INSERT SUPPORT_AUDIT (STATUS_CONFIRMED).
--   Regla 3  Solo APPROVED, neto = 2 x monto, CONFIRMED
--            -> INSERT LEDGER_ENTRY tipo REVERSAL por el monto del pago, con
--               related_entry_id = DEBIT más reciente sin reverso
--               + INSERT SUPPORT_AUDIT (DUPLICATE_DEBIT_REVERSED).
--   Regla 6  Solo DECLINED, neto = 0, PENDING
--            -> UPDATE PAYMENT a REJECTED + INSERT SUPPORT_AUDIT (STATUS_REJECTED).
--   Reglas 4, 5, 7 y 9 (revisión manual)
--            -> INSERT SUPPORT_AUDIT (MANUAL_REVIEW) solo si no existe ya uno
--               para ese pago.
--   No escribe nada en: regla 1 (consistente), regla 8 (evidencia
--   insuficiente) ni cuando el pago no existe.
--
-- Garantías:
--   - Bloquea la fila del pago con SELECT ... FOR UPDATE antes de leer la
--     evidencia, así dos conciliaciones del mismo pago se serializan.
--   - Nunca hace UPDATE ni DELETE sobre LEDGER_ENTRY: solo inserta REVERSAL.
--   - Atómico: si algo falla, deshace todo lo hecho por la llamada
--     (ROLLBACK TO SAVEPOINT) y propaga el error.
--   - No hace COMMIT: la transacción la confirma quien llama, igual que
--     @Transactional en Java. El bloqueo se libera con ese COMMIT/ROLLBACK.
--
-- Requiere Oracle 12c o superior (FETCH FIRST).
-- =============================================================================

CREATE OR REPLACE PROCEDURE reconcile_payment (
    p_external_ref IN  payment.external_ref%TYPE,
    p_result       OUT VARCHAR2,
    p_reason       OUT VARCHAR2
) AS
    c_action_confirm   CONSTANT support_audit.action%TYPE := 'STATUS_CONFIRMED';
    c_action_reject    CONSTANT support_audit.action%TYPE := 'STATUS_REJECTED';
    c_action_reversal  CONSTANT support_audit.action%TYPE := 'DUPLICATE_DEBIT_REVERSED';
    c_action_manual    CONSTANT support_audit.action%TYPE := 'MANUAL_REVIEW';

    v_payment_id   payment.id%TYPE;
    v_status       payment.status%TYPE;
    v_amount       payment.amount%TYPE;
    v_net_debit    NUMBER;
    v_approved     PLS_INTEGER;
    v_declined     PLS_INTEGER;
    v_debit_id     ledger_entry.id%TYPE;
    v_now          TIMESTAMP := LOCALTIMESTAMP;

    v_only_approved BOOLEAN;
    v_only_declined BOOLEAN;
    v_no_events     BOOLEAN;

    -- Registra la decisión de revisión manual sin duplicar la auditoría.
    PROCEDURE manual_review (p_text IN VARCHAR2) IS
        v_exists PLS_INTEGER;
    BEGIN
        SELECT COUNT(*)
        INTO   v_exists
        FROM   support_audit
        WHERE  payment_id = v_payment_id
        AND    action     = c_action_manual;

        IF v_exists = 0 THEN
            INSERT INTO support_audit (id, payment_id, action, reason, created_at)
            VALUES (support_audit_seq.NEXTVAL, v_payment_id, c_action_manual, p_text, v_now);
        END IF;

        p_result := 'REVISION_MANUAL';
        p_reason := p_text;
    END manual_review;

    -- Cambia el estado del pago y deja constancia en la auditoría.
    PROCEDURE change_status (p_new_status IN VARCHAR2, p_action IN VARCHAR2, p_text IN VARCHAR2) IS
    BEGIN
        UPDATE payment
        SET    status      = p_new_status,
               updated_at  = v_now,
               row_version = row_version + 1   -- mantiene coherente el @Version de JPA
        WHERE  id = v_payment_id;

        INSERT INTO support_audit (id, payment_id, action, reason, created_at)
        VALUES (support_audit_seq.NEXTVAL, v_payment_id, p_action, p_text, v_now);

        p_result := 'CORREGIDO';
        p_reason := p_text;
    END change_status;

BEGIN
    SAVEPOINT sp_reconcile_payment;

    -- 1. Bloquear el pago. Si no existe, se responde sin escribir nada.
    BEGIN
        SELECT id, status, amount
        INTO   v_payment_id, v_status, v_amount
        FROM   payment
        WHERE  external_ref = p_external_ref
        FOR UPDATE;
    EXCEPTION
        WHEN NO_DATA_FOUND THEN
            p_result := 'NO_ENCONTRADO';
            p_reason := 'Pago no encontrado';
            RETURN;
    END;

    -- 2. Evidencia: débito neto (DEBIT - REVERSAL) y eventos del proveedor.
    SELECT NVL(SUM(CASE entry_type WHEN 'DEBIT' THEN amount WHEN 'REVERSAL' THEN -amount END), 0)
    INTO   v_net_debit
    FROM   ledger_entry
    WHERE  payment_id = v_payment_id;

    SELECT COUNT(CASE WHEN provider_status = 'APPROVED' THEN 1 END),
           COUNT(CASE WHEN provider_status = 'DECLINED' THEN 1 END)
    INTO   v_approved, v_declined
    FROM   provider_event
    WHERE  payment_id = v_payment_id;

    v_only_approved := v_approved > 0 AND v_declined = 0;
    v_only_declined := v_declined > 0 AND v_approved = 0;
    v_no_events     := v_approved = 0 AND v_declined = 0;

    -- 3. Tabla de decisión (mismo orden que el servicio Java).

    -- Regla 4: APPROVED y DECLINED, cualquier débito y estado.
    IF v_approved > 0 AND v_declined > 0 THEN
        manual_review('Eventos APPROVED y DECLINED contradictorios');

    -- Regla 1: solo APPROVED, neto = monto, CONFIRMED.
    ELSIF v_only_approved AND v_net_debit = v_amount AND v_status = 'CONFIRMED' THEN
        p_result := 'CONSISTENTE';
        p_reason := 'Pago confirmado con débito neto igual al monto';

    -- Regla 2: solo APPROVED, neto = monto, PENDING.
    ELSIF v_only_approved AND v_net_debit = v_amount AND v_status = 'PENDING' THEN
        change_status('CONFIRMED', c_action_confirm,
                      'Proveedor aprobó y existe el débito; estado actualizado a CONFIRMED');

    -- Regla 3: solo APPROVED, neto = 2 x monto, CONFIRMED.
    ELSIF v_only_approved AND v_net_debit = v_amount * 2 AND v_status = 'CONFIRMED' THEN
        BEGIN
            SELECT d.id
            INTO   v_debit_id
            FROM   ledger_entry d
            WHERE  d.payment_id = v_payment_id
            AND    d.entry_type = 'DEBIT'
            AND    NOT EXISTS (SELECT 1
                               FROM   ledger_entry r
                               WHERE  r.entry_type       = 'REVERSAL'
                               AND    r.related_entry_id = d.id)
            ORDER  BY d.created_at DESC, d.id DESC
            FETCH  FIRST 1 ROWS ONLY;

            INSERT INTO ledger_entry (id, payment_id, entry_type, amount, related_entry_id, created_at)
            VALUES (ledger_entry_seq.NEXTVAL, v_payment_id, 'REVERSAL', v_amount, v_debit_id, v_now);

            INSERT INTO support_audit (id, payment_id, action, reason, created_at)
            VALUES (support_audit_seq.NEXTVAL, v_payment_id, c_action_reversal,
                    'Débito duplicado; se registró un REVERSAL por el monto del pago', v_now);

            p_result := 'CORREGIDO';
            p_reason := 'Débito duplicado; se registró un REVERSAL por el monto del pago';
        EXCEPTION
            WHEN NO_DATA_FOUND THEN
                manual_review('Débito duplicado sin DEBIT disponible para revertir');
        END;

    -- Regla 5: solo APPROVED, neto = 0, PENDING.
    ELSIF v_only_approved AND v_net_debit = 0 AND v_status = 'PENDING' THEN
        manual_review('Proveedor aprobó pero no hay débito en el ledger');

    -- Regla 6: solo DECLINED, neto = 0, PENDING.
    ELSIF v_only_declined AND v_net_debit = 0 AND v_status = 'PENDING' THEN
        change_status('REJECTED', c_action_reject,
                      'Proveedor rechazó y no hay débito; estado actualizado a REJECTED');

    -- Regla 7: solo DECLINED, neto > 0, cualquier estado.
    ELSIF v_only_declined AND v_net_debit > 0 THEN
        manual_review('Proveedor rechazó pero existe débito neto');

    -- Regla 8: sin eventos, neto = 0, PENDING. La tabla indica "Nada".
    ELSIF v_no_events AND v_net_debit = 0 AND v_status = 'PENDING' THEN
        p_result := 'CONSISTENTE';
        p_reason := 'Pago pendiente sin eventos ni débitos; evidencia insuficiente, sin acción';

    -- Regla 9: cualquier otro caso.
    ELSE
        manual_review('Caso no contemplado en la tabla de decisión');
    END IF;

EXCEPTION
    WHEN OTHERS THEN
        -- Deshace solo lo hecho por esta llamada y deja el error a quien llama.
        ROLLBACK TO sp_reconcile_payment;
        RAISE;
END reconcile_payment;
/
