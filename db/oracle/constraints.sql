-- =============================================================================
-- constraints.sql
-- Índice único sobre LEDGER_ENTRY.related_entry_id.
--
-- Para qué sirve:
--   Un REVERSAL apunta al DEBIT que revierte mediante related_entry_id. Con
--   este índice, la base de datos rechaza (ORA-00001) un segundo REVERSAL sobre
--   el mismo DEBIT, aunque lo intente un proceso que no pase por el bloqueo del
--   pago (otro servicio, un script manual o un reintento mal controlado). Es la
--   última línea de defensa contra un doble reverso, que dejaría el débito
--   neto por debajo del monto.
--
-- Los DEBIT tienen related_entry_id NULL. Oracle no guarda en un índice B-tree
-- las filas cuya clave es completamente NULL, así que el índice no impide
-- tener muchos DEBIT: solo exige que cada related_entry_id no nulo sea único.
-- =============================================================================

-- Comprobación previa: debe devolver 0 filas. Si devuelve alguna, ya hay
-- débitos reversados más de una vez y la creación del índice fallará.
SELECT related_entry_id, COUNT(*) AS reversals
FROM   ledger_entry
WHERE  related_entry_id IS NOT NULL
GROUP  BY related_entry_id
HAVING COUNT(*) > 1;

CREATE UNIQUE INDEX ux_ledger_entry_related_entry
    ON ledger_entry (related_entry_id);
