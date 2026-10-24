# Conciliación de pagos PagoClaro

Herramienta de soporte para conciliar pagos inconsistentes. Para una referencia de pago, compara el estado del pago con los eventos del proveedor y el débito neto del ledger (DEBIT − REVERSAL), y aplica una tabla de decisión: deja el pago como está, lo corrige o lo envía a revisión manual, dejando constancia en `SUPPORT_AUDIT`. Incluye una API REST en Spring Boot sobre H2 en modo Oracle y los scripts equivalentes para Oracle en `db/oracle`.

Evidencia del incidente

hechos observados
Se puede observar que el pago con referencia PAY-BX17 tuvo una actualización del proveedor de pagos que fallo por timeout, luego se ejecuto un reintento programado el cual finalizo con la actualización en la tabla PROVIDER_EVENT con estado aproved y se creo un registro de tipo debit en la tabla ledgerEntry, luego de esto se evidencia un log que indica que el estado del pago ya estaba confirmado durante el reintento y al ejecutarse  reconciliation job se observa que el debito neto era de 178000 y no corresponde con el pago esperado el cual debería tener un valor de 89000. en la tabla payment se observan 2 movimientos de debito que generan esta inconsistencia, los débitos esta registrados en la tabla ledgerEntry. respecto a los otros pagos no hay evidencia suficiente en los logs para determinar porque ocurrieron inconsistencias en ellos.

hipótesis de causa
- para el pago PAY-BX17 es posible que el log 2026-10-13 09:41:25.317 INFO  paymentRef=PAY-BX17 providerEvent=EVT-B2 status=APPROVED 
received no corresponda al reintento si no que sea del evento que llego a las 09:41:22.104, lo mismo que el debito indicado por el log 026-10-13 09:41:25.390 INFO  paymentRef=PAY-BX17 ledgerEntry=5003 type=DEBIT created y que el reintento haya generado esto también ocasionando asi la duplicidad de los del estado y del debito. tal vez el sistema no tiene una regla de validación en las tablas ledgerEntry y provider_event en caso de reintentos y solo se fija en el estado de la tabla payment porque el sistema si genero un warging para el estado aproved.
-en el pago PAY-AX91 tal vez fallo la actualización de estado en payment ya que el sistema si pudo registrar el evento del proveedor de pagos y su log en ledgerEntry. aunque no se cuenta con la información suficiente para determinar la causa del fallo pero si con la información suficiente para actualizar el estado en una conciliación.
- el pago PAY-CQ22 presenta información contradictoria, posiblemente se hicieron reintentos manuales mientras el sistema se encontraba fallando ocasionando que se acumularan 2 eventos con diferente estado en este pago.

validaciones que haría
- para el pago PAY-BX17 revisaría en las tablas provider_event y ledgerEntry los timestamps de cada registro asociado al pago para poder determinar si algúno de los registro fue demora del fallo por timeout o falta de validaciones en el reintento(esto lo reviso en el código o stored procedures), además de esto buscaría mas logs y consultaría con el proveedor que información me podrían enviar para determinar correctamente la causa del fallo.
- con el PAY-AX91 verificaria los logs a ver si el proveedor envio tarde sus eventos porque la inforamacion de este si llego pero como la tabla de payment no actualizo su estado posiblemente pueda encontrar la respuesta en los logs y sus timestamps.
- para el pago PAY-CQ22 revisaría la tabla support_audi para confirmar si hubo acciones manuales de este pago durante el degrado de servicios y consultaría directamente con el proveedor de pagos los logs para saber si se trato de una demora en la llegada de los enventos por reintentos manuales y cual seria el estado oficial de este pago.

corrección propuesta
inicialmente actualizaria de manera manual los pagos mas criticos de la mano de soporte y el proveedor de pagos como solucion inmediata en caso de ser necesario y viable porque si son cientos de pagos no seria muy viable. 
luego agregaria reglas de validacion al sistema para que valide si exiten debitos antes de crear otros, esto se podria hacer mediantes stored procedures para agregar las reglas de validacion directamente en la base de datos y no por codigo, esto tambien facilitaria actualizaciones a futuro ya que solo se deberia actualizar el SP y el codigo unicamente lo llamaria y corregiria la logica en reintentos por timeout teniendo en cuenta que puede pasar que los eventos lleguen tarde.

Nota operativa para soporte

cuándo es seguro conciliar: soporte sabe que un caso es seguro de conciliar cuando no hay cabida a dudas ni suposiciones con base en la información de las tablas. la tabla de decisión sirve como una guía para saber cuándo se considera un caso seguro.

cuándo detenerse:

si la herramienta responde REVISION_MANUAL no se debe volver a ejecutar esperando otro resultado, el caso debe revisarse con el proveedor de pagos antes de hacer cualquier cambio.
si la respuesta es NO_ENCONTRADO se debe confirmar que la referencia esté bien escrita antes de escalar.
si hay errores en la ejecución o resultados que no coinciden con la tabla de decisión no se deben hacer correcciones manuales en las tablas, se debe escalar al equipo de desarrollo.
nunca se deben borrar ni modificar registros de LEDGER_ENTRY a mano.

qué revisar después de ejecutar:

en SUPPORT_AUDIT que haya quedado el registro de la acción realizada.
en LEDGER_ENTRY que el débito neto del pago sea igual al monto si el pago quedó confirmado, y que el REVERSAL apunte al débito correcto.
volver a ejecutar la consulta de detección (db/oracle/detection_query.sql) y confirmar que el pago ya no aparece, o que solo aparece si quedó en revisión manual.

Uso de IA

usé ChatGPT en chat como guía para entender los conceptos que no dominaba a fondo (idempotencia, bloqueo pesimista y optimista, transacciones en Spring), revisar mi análisis del incidente y construir la tabla de decisión. el diagnóstico, las reglas de la tabla de decisión y las decisiones técnicas las tomé yo.

usé Claude Code para generar el código, las pruebas y los scripts de base de datos, trabajando por fases y revisando cada una antes de hacer el commit. el detalle de cada fase está en la tabla de abajo.

cómo lo validé: ejecuté las pruebas automatizadas, probé el endpoint con los 4 casos de muestra y con una referencia inexistente, revisé los datos en la consola de H2 después de ejecutar dos veces la conciliación, y rompí a propósito una regla del servicio para confirmar que las pruebas fallaban.


## Cómo ejecutar y probar

### Requisitos

- JDK 21.
- No hace falta instalar Maven: el proyecto incluye el wrapper (`mvnw.cmd` en Windows y `mvnw` en Linux/macOS).
- El puerto 8080 libre. Si está ocupado, ver la nota del paso siguiente.

### Arrancar la aplicación

```powershell
.\mvnw.cmd spring-boot:run
```

Al arrancar, H2 crea el esquema (`src/main/resources/schema.sql`) y carga los 4 pagos de muestra (`src/main/resources/data.sql`). La base vive en memoria: cada reinicio vuelve a los datos iniciales.

Para usar otro puerto:

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--server.port=8081"
```

### Ejecutar las pruebas

```powershell
.\mvnw.cmd test
```

Ejecuta `ReconciliationIntegrationTest`, que cubre los 4 casos de muestra, la idempotencia de PAY-BX17, la auditoría única de PAY-CQ22, el 404 y el `related_entry_id` del REVERSAL. Antes de cada prueba se recargan los datos, así que las pruebas no dependen unas de otras.

### Consola H2

- URL: http://localhost:8080/h2-console
- JDBC URL: `jdbc:h2:mem:reconciliation`
- Usuario: `sa`, contraseña vacía.

Consulta útil para ver el estado de cada pago:

```sql
SELECT p.external_ref, p.status, p.amount,
       (SELECT COALESCE(SUM(CASE l.entry_type WHEN 'DEBIT' THEN l.amount ELSE -l.amount END), 0)
          FROM LEDGER_ENTRY l WHERE l.payment_id = p.id) AS debito_neto,
       (SELECT COUNT(*) FROM SUPPORT_AUDIT a WHERE a.payment_id = p.id) AS auditorias
FROM PAYMENT p ORDER BY p.id;
```

### Llamar al endpoint (PowerShell)

`POST /support/payments/{externalRef}/reconcile`

```powershell
Invoke-RestMethod -Method Post -Uri "http://localhost:8080/support/payments/PAY-BX17/reconcile"
```

Respuesta:

```json
{ "externalRef": "PAY-BX17", "result": "CORREGIDO", "reason": "Débito duplicado; se registró un REVERSAL por el monto del pago" }
```

En Windows PowerShell 5.1, `Invoke-RestMethod` lanza una excepción cuando la respuesta es un 404. Para ver el código:

```powershell
try {
    Invoke-RestMethod -Method Post -Uri "http://localhost:8080/support/payments/PAY-NO-EXISTE/reconcile"
} catch {
    $_.Exception.Response.StatusCode.value__   # 404
}
```

Resultados esperados con los datos iniciales:

| Referencia | 1ª llamada | 2ª llamada |
|---|---|---|
| PAY-AX91 | CORREGIDO (pasa a CONFIRMED) | CONSISTENTE |
| PAY-BX17 | CORREGIDO (REVERSAL de 89000) | CONSISTENTE |
| PAY-CQ22 | REVISION_MANUAL | REVISION_MANUAL (sin duplicar la auditoría) |
| PAY-DK44 | CONSISTENTE | CONSISTENTE |
| Inexistente | 404 NO_ENCONTRADO | 404 NO_ENCONTRADO |

## Decisiones técnicas

La tabla de decisión que aplican el servicio Java y el procedimiento PL/SQL está en el archivo Excel del repositorio, `tabla de desiciones .xlsx`:

| # | Proveedor | Débito neto | Estado | Acción |
|---|---|---|---|---|
| 1 | Solo APPROVED | = monto | CONFIRMED | Nada, consistente |
| 2 | Solo APPROVED | = monto | PENDING | Estado → CONFIRMED |
| 3 | Solo APPROVED | 2 × monto | CONFIRMED | REVERSAL del DEBIT sobrante |
| 4 | APPROVED y DECLINED | cualquiera | cualquiera | Revisión manual |
| 5 | Solo APPROVED | 0 | PENDING | Revisión manual |
| 6 | Solo DECLINED | 0 | PENDING | Estado → REJECTED |
| 7 | Solo DECLINED | > 0 | cualquiera | Revisión manual |
| 8 | Ninguno | 0 | PENDING | Nada, evidencia insuficiente |
| 9 | Cualquier otro caso | | | Revisión manual |

### 1. Bloqueo pesimista, con `@Version` como segunda capa

- **Contexto:** el incidente de PAY-BX17 se originó por dos procesos que actuaron sobre el mismo pago casi a la vez. Si dos conciliaciones del mismo pago corren en paralelo, ambas pueden ver el débito duplicado y crear dos REVERSAL.
- **Alternativas:** bloqueo optimista solo con `@Version` y reintentos, o bloqueo pesimista.
- **Decisión:** `PaymentRepository.findByExternalRefForUpdate` aplica `PESSIMISTIC_WRITE` (`SELECT ... FOR UPDATE`) antes de leer la evidencia. Así la segunda conciliación espera a que termine la primera y ve sus cambios. `row_version` se mapea con `@Version` como protección extra frente a otros procesos que actualicen el pago sin bloquearlo.
- **Trade-offs:** las conciliaciones de un mismo pago se serializan. A cambio, no hace falta lógica de reintento.
- **Riesgos:** el bloqueo no tiene tiempo de espera máximo, así que una transacción larga deja esperando a las demás. El bloqueo solo protege a quien lo pide: un proceso que inserte en el ledger sin bloquear el pago no queda cubierto (de ahí el índice único de la decisión 7).

### 2. `@Immutable` en `LedgerEntry` y repositorio sin `delete`

- **Contexto:** los movimientos contables no se modifican ni se borran. Una corrección es un movimiento nuevo (REVERSAL).
- **Alternativas:** confiar en la disciplina del código, o usar permisos de base de datos.
- **Decisión:** `LedgerEntry` lleva `@Immutable` (Hibernate ignora sus cambios) y no tiene setters. `LedgerEntryRepository` extiende `Repository` en lugar de `JpaRepository`, por lo que solo expone `save` y lecturas.
- **Trade-offs:** corregir un movimiento mal creado obliga a crear un reverso, incluso en desarrollo.
- **Riesgos:** es una protección de la aplicación. SQL directo o el `EntityManager` pueden saltársela. En producción debería reforzarse con permisos (sin `UPDATE`/`DELETE` sobre `LEDGER_ENTRY` para el usuario de la aplicación).

### 3. H2 en modo Oracle

- **Contexto:** el sistema real usa Oracle, pero el proyecto debe poder ejecutarse y probarse sin instalar nada.
- **Alternativas:** Oracle en Docker/Testcontainers, o H2 sin modo de compatibilidad.
- **Decisión:** H2 en memoria con `MODE=Oracle`. El esquema usa tipos de Oracle (`VARCHAR2`, `NUMBER`), secuencias y `LOCALTIMESTAMP - INTERVAL`, así que `schema.sql` y `data.sql` también sirven en Oracle. Hibernate no genera el esquema (`ddl-auto=none`).
- **Trade-offs:** las pruebas son rápidas y no tienen dependencias externas, a costa de no ejecutarse sobre el motor real.
- **Riesgos:** H2 no reproduce todo el comportamiento de Oracle (bloqueos, tiempos de espera, PL/SQL). Por eso el procedimiento PL/SQL no puede probarse en H2.

### 4. Reglas 5 y 7 a revisión manual

- **Contexto:** en la regla 5 el proveedor aprobó pero no hay débito; en la regla 7 el proveedor rechazó pero hay débito.
- **Alternativas:** corregir automáticamente, creando el DEBIT en la regla 5 o un REVERSAL en la regla 7.
- **Decisión:** revisión manual, tal como indica la tabla de decisión. La herramienta no crea ni devuelve dinero cuando el proveedor y el ledger se contradicen.
- **Trade-offs:** requiere intervención de soporte en casos que quizá podrían resolverse solos.
- **Riesgos:** si soporte no atiende la revisión, el pago queda inconsistente. La auditoría `MANUAL_REVIEW` es la señal para hacer ese seguimiento.

### 5. Regla 8 como CONSISTENTE

- **Contexto:** la tabla dice "Nada, evidencia insuficiente" para un pago PENDING sin eventos ni débitos. La API solo tiene los resultados CONSISTENTE, CORREGIDO, REVISION_MANUAL y NO_ENCONTRADO.
- **Alternativas:** devolver REVISION_MANUAL, o añadir un resultado nuevo (por ejemplo `SIN_ACCION`).
- **Decisión:** devolver CONSISTENTE con el motivo "evidencia insuficiente, sin acción", sin escribir nada. El estado es coherente: el pago está pendiente y no hay evidencia de lo contrario.
- **Trade-offs:** no se amplía la API, pero el resultado no distingue "todo bien" de "aún no hay datos"; esa diferencia solo aparece en el motivo.
- **Riesgos:** un pago que se queda en PENDING para siempre porque el proveedor nunca envió el evento no aparece como problema.

### 6. Procedimiento PL/SQL sin `COMMIT`

- **Contexto:** `reconcile_payment` debe ser atómico y también poder llamarse desde otra transacción.
- **Alternativas:** hacer `COMMIT` dentro del procedimiento, o usar una transacción autónoma.
- **Decisión:** el procedimiento no hace `COMMIT`. Marca un `SAVEPOINT` al empezar; si algo falla, deshace solo lo suyo con `ROLLBACK TO` y vuelve a lanzar el error. Quien llama confirma, igual que con `@Transactional` en Java.
- **Trade-offs:** quien llama puede componerlo con otras operaciones, pero tiene la responsabilidad de hacer `COMMIT`.
- **Riesgos:** si quien llama olvida el `COMMIT`, los cambios se pierden y el bloqueo del pago se mantiene hasta que termine la sesión.

### 7. Índice único sobre `related_entry_id`

- **Contexto:** reversar dos veces el mismo DEBIT dejaría el débito neto por debajo del monto.
- **Alternativas:** confiar solo en el bloqueo del pago, o usar un trigger de validación.
- **Decisión:** índice único `ux_ledger_entry_related_entry` en Oracle (`db/oracle/constraints.sql`). Los DEBIT tienen `related_entry_id` NULL, y Oracle no indexa las claves que son totalmente NULL, así que los DEBIT no entran en conflicto entre sí.
- **Trade-offs:** un DEBIT solo puede tener un reverso, aunque sea parcial. Para reversos parciales habría que cambiar el modelo.
- **Riesgos:** hay que comprobar antes que no existan reversos duplicados, o la creación del índice falla. Por ahora solo está en el script de Oracle, no en el `schema.sql` de H2, así que las pruebas Java no lo cubren.

## Supuestos y riesgos

### Supuestos

- La tabla de decisión del Excel está completa y es la fuente de verdad. Todo lo que no cubre va a revisión manual.
- Todos los montos están en la misma moneda y se comparan por igualdad exacta (`BigDecimal.compareTo` en Java y `=` sobre `NUMBER(18,2)` en Oracle).
- Los eventos del proveedor son la evidencia externa válida, y su `provider_event_id` es único.
- La regla 3 solo aplica cuando el débito neto es exactamente el doble del monto. Con un triple o un débito parcial, el pago va a revisión manual.
- El REVERSAL se crea por el monto del pago y apunta al DEBIT más reciente que todavía no tiene reverso.
- La ventana de 24 horas de la consulta de detección usa la hora de la sesión (`LOCALTIMESTAMP`).

### Riesgos

- **El endpoint no tiene autenticación ni autorización.** Puede cambiar estados y crear movimientos contables, así que no debe exponerse fuera de un entorno controlado sin protegerlo.
- **La consola H2 está habilitada.** Es solo para desarrollo y debe desactivarse en cualquier otro entorno.
- **Un solo `MANUAL_REVIEW` por pago.** Si el pago vuelve a revisión manual por un motivo distinto, el motivo nuevo no se registra.
- **La herramienta corrige los síntomas, no la causa.** El reintento que duplicó el débito sigue sin control de idempotencia en el sistema origen.
- **Los datos son de muestra y viven en memoria.** No representan volumen ni casos reales.

## Validación de los scripts Oracle

Los scripts de `db/oracle` no se ejecutaron porque no había Oracle disponible. Este es el plan para validarlos en un Oracle real (por ejemplo, la imagen `gvenzl/oracle-free` con SQL Developer o SQLcl):

1. **Preparación.** Ejecutar `src/main/resources/schema.sql` y `data.sql` (son compatibles con Oracle), después `constraints.sql` y `reconcile_payment.sql`. Comprobar que el procedimiento compila: `SELECT status FROM user_objects WHERE object_name = 'RECONCILE_PAYMENT'` debe devolver `VALID`.
2. **Detección.** Ejecutar `detection_query.sql`. Deben salir PAY-AX91 (aprobado pero no confirmado), PAY-BX17 (débito neto distinto del monto, diferencia de 89000) y PAY-CQ22 (evidencia contradictoria). PAY-DK44 no debe aparecer.
3. **Procedimiento.** Llamarlo desde un bloque anónimo con `DBMS_OUTPUT` para cada referencia. Resultados esperados: AX91 → CORREGIDO, BX17 → CORREGIDO, CQ22 → REVISION_MANUAL, DK44 → CONSISTENTE, y una referencia inexistente → NO_ENCONTRADO sin filas nuevas en `SUPPORT_AUDIT`. Hacer `COMMIT` y repetir las llamadas: BX17 debe dar CONSISTENTE, sin filas nuevas en `LEDGER_ENTRY` ni en `SUPPORT_AUDIT`, y CQ22 debe seguir con un solo `MANUAL_REVIEW`. Repetir la detección: solo debe quedar PAY-CQ22.
4. **Bloqueo.** En la sesión A, llamar al procedimiento para PAY-BX17 sin hacer `COMMIT`. En la sesión B, hacer la misma llamada: debe quedarse esperando. Hacer `COMMIT` en A: B debe continuar, devolver CONSISTENTE y no crear un segundo REVERSAL.
5. **Índice.** Insertar a mano un segundo REVERSAL con el mismo `related_entry_id`. Debe fallar con `ORA-00001`.
6. **Atomicidad.** Provocar un error a mitad de la llamada, por ejemplo con un trigger temporal que falle al insertar en `SUPPORT_AUDIT`. El estado del pago y el ledger no deben cambiar.

## Pendientes y limitaciones

| Limitación | Impacto | Siguiente paso |
|---|---|---|
| Las reglas 5, 6, 7 y 8 no tienen datos de prueba: los 4 pagos de muestra solo cubren las reglas 1 a 4. | Esas ramas del servicio y del procedimiento no se han ejecutado nunca. | Añadir pagos de prueba para cada regla en un script solo de pruebas (`src/test/resources`), sin tocar `data.sql`, y una prueba de integración por regla. |
| No hay prueba automatizada de concurrencia. | El bloqueo pesimista solo se ha comprobado leyendo el código. | Escribir una prueba que lance dos conciliaciones de PAY-BX17 en paralelo (dos hilos sincronizados con `CountDownLatch`) y verifique que solo existe un REVERSAL. Idealmente, contra Oracle con Testcontainers. |
| Los scripts Oracle no se han ejecutado. | Pueden tener errores de compilación o de comportamiento. | Ejecutar el plan de 6 pasos de la sección anterior, y después automatizarlo con Testcontainers (`gvenzl/oracle-free`) dentro de la suite de pruebas. |

## Registro de ayudas de IA

Aquí se registra cada ayuda recibida de un asistente de IA (Claude Code) durante el desarrollo: qué se pidió, qué se generó y qué decisiones se tomaron.

| Fecha | Solicitud | Qué se generó / cambió | Decisiones y notas |
|-------|-----------|------------------------|--------------------|
| 2026-10-23 | Crear esquema y datos de prueba en H2 compatibles con Oracle, y configurar la carga al arrancar | `src/main/resources/schema.sql` (tablas PAYMENT, PROVIDER_EVENT, LEDGER_ENTRY, SUPPORT_AUDIT), `src/main/resources/data.sql` (casos PAY-AX91, PAY-BX17, PAY-CQ22, PAY-DK44) y `application.properties` (H2 en `MODE=Oracle`, scripts al arrancar, consola en `/h2-console`) | Tipos de Oracle (`VARCHAR2`, `NUMBER`), restricciones con nombre y `CHECK` para los estados. Se añadieron índices por `payment_id` y secuencias que empiezan en 100 (no pedidos). Fechas relativas al arranque (`LOCALTIMESTAMP - INTERVAL`) para que siempre caigan en las últimas 24 h. `ddl-auto=none` para que Hibernate no toque el esquema. Verificado con `mvnw test` y una consulta en H2 |
| 2026-10-23 | Crear en el README un apartado para registrar las ayudas de IA | Esta sección | Cada ayuda nueva se añade como una fila a esta tabla |
| 2026-10-23 | Crear una entidad y un repositorio por tabla (secuencias con `allocationSize = 1`, `payment_id` como campo simple, `@Version` en PAYMENT, `@Immutable` en LEDGER_ENTRY) | Paquete `domain`: `Payment`, `ProviderEvent`, `LedgerEntry`, `SupportAudit` y los enums `PaymentStatus`, `ProviderStatus`, `LedgerEntryType`. Paquete `repository`: `PaymentRepository.findByExternalRefForUpdate` (`PESSIMISTIC_WRITE`), `ProviderEventRepository.findByPaymentIdOrderByReceivedAtAsc`, `LedgerEntryRepository.findByPaymentIdOrderByCreatedAtAsc` y `SupportAuditRepository.existsByPaymentIdAndAction` | Sin `@ManyToOne` ni cascadas. Se usan enums para los estados en lugar de `String`. `LedgerEntryRepository` extiende `Repository`, no `JpaRepository`, para exponer solo `save` y lecturas (sin `delete`). El método con bloqueo tiene nombre propio (`...ForUpdate`) para no bloquear en lecturas normales y debe llamarse dentro de una transacción. Se tuvo en cuenta la tabla de decisiones: el REVERSAL referencia al DEBIT mediante `related_entry_id`. Verificado con un test temporal (lecturas, secuencias que empiezan en 100, incremento de `row_version`) que luego se eliminó |
| 2026-10-23 | Crear `ReconciliationService`, `ReconciliationController` (POST `/support/payments/{externalRef}/reconcile`) y `ReconciliationResult` aplicando la tabla de decisiones | `service/ReconciliationService.java`, `service/ReconciliationResult.java`, `web/ReconciliationController.java` y propiedades `server.error.*` en `application.properties` | Las 9 reglas se aplican en orden y el caso por defecto es revisión manual. Ninguna regla usa la referencia. Regla 3: REVERSAL por el monto del pago, ligado al DEBIT más reciente sin reverso; si no existe ese DEBIT, pasa a revisión manual. Regla 8 ("Nada, evidencia insuficiente") se devuelve como `CONSISTENTE` sin escribir nada, porque la API no tiene otro resultado para "sin acción". Auditoría con acciones `STATUS_CONFIRMED`, `STATUS_REJECTED`, `DUPLICATE_DEBIT_REVERSED` y `MANUAL_REVIEW` (esta última sin duplicar). Logs solo con referencia, decisión y motivo, sin montos ni ids; se sanean los saltos de línea de la referencia. Errores sin trazas ni mensajes internos. Verificado con curl (AX91 → CORREGIDO, BX17 → CORREGIDO, CQ22 → REVISION_MANUAL, DK44 → CONSISTENTE, referencia inexistente → 404; en la segunda pasada todos quedan consistentes, salvo CQ22) y con un test temporal sobre SUPPORT_AUDIT y el REVERSAL, que luego se eliminó |
| 2026-10-23 | Crear pruebas de integración (JUnit + Spring Boot + H2 con `data.sql`) para los 4 casos, la idempotencia de PAY-BX17, la auditoría única de PAY-CQ22, el 404 y el `related_entry_id` del REVERSAL | `src/test/java/.../ReconciliationIntegrationTest.java` (8 pruebas con MockMvc y JdbcTemplate) y `src/test/resources/reset.sql` | Para que cada prueba sea independiente, antes de cada una se vacían las tablas, se reinician las secuencias y se recarga `data.sql` (`@Sql`). Así los cambios se confirman de verdad en lugar de revertirse con `@Transactional`. La clase usa su propia base H2 (`reconciliation-it`), porque compartir la base en memoria con el otro contexto de pruebas hacía fallar `schema.sql` ("Tabla PAYMENT ya existe"). Las comprobaciones usan SQL directo y buscan por `external_ref`, sin ids fijos. El DEBIT esperado del REVERSAL se calcula antes de conciliar. `mvnw test`: 9 pruebas, 0 fallos; también pasan en orden aleatorio |
| 2026-10-23 | Crear scripts Oracle en `db/oracle`: consulta de detección, procedimiento PL/SQL de conciliación e índice único sobre `related_entry_id` | `db/oracle/detection_query.sql`, `db/oracle/reconcile_payment.sql` y `db/oracle/constraints.sql` | Detección: pagos con actividad en las últimas 24 h (en el pago, en los eventos o en el ledger), clasificados en `amount_issue` y `status_issue`; con los datos de muestra deben salir AX91, BX17 y CQ22, pero no DK44. Procedimiento: las mismas 9 reglas, acciones de auditoría y motivos que el servicio Java; bloquea con `SELECT ... FOR UPDATE`; solo inserta en `LEDGER_ENTRY`; revisa `MANUAL_REVIEW` antes de insertarlo; usa `SAVEPOINT` + `ROLLBACK TO` y vuelve a lanzar el error para ser atómico; no hace `COMMIT` (lo hace quien llama, igual que `@Transactional`); incrementa `row_version` para no romper el `@Version` de JPA; requiere Oracle 12c+. Índice único: Oracle no indexa claves totalmente NULL, así que los DEBIT no chocan entre sí; incluye una consulta previa para detectar reversos duplicados existentes. No se ejecutaron: en el equipo no hay Oracle ni Docker |
| 2026-10-23 | Completar el README sin tocar el diagnóstico ni el registro de ayudas | Título y descripción al inicio. Después del diagnóstico: "Cómo ejecutar y probar", "Decisiones técnicas" (7 decisiones con contexto, alternativas, decisión, trade-offs y riesgos, y la tabla de decisión del Excel), "Supuestos y riesgos", "Validación de los scripts Oracle" y "Pendientes y limitaciones" | Solo se añadió texto: `git diff` no muestra líneas eliminadas. Se añadieron como riesgos el endpoint sin autenticación, la consola H2 habilitada y que solo se guarda un `MANUAL_REVIEW` por pago. El ejemplo en PowerShell muestra cómo leer el 404, porque en PowerShell 5.1 `Invoke-RestMethod` lanza una excepción |
