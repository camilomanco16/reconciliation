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
## Registro de ayudas de IA

Aquí se registra cada ayuda recibida de un asistente de IA (Claude Code) durante el desarrollo: qué se pidió, qué se generó y qué decisiones se tomaron.

| Fecha | Solicitud | Qué se generó / cambió | Decisiones y notas |
|-------|-----------|------------------------|--------------------|
| 2026-10-23 | Crear esquema y datos de prueba en H2 compatibles con Oracle, y configurar la carga al arrancar | `src/main/resources/schema.sql` (tablas PAYMENT, PROVIDER_EVENT, LEDGER_ENTRY, SUPPORT_AUDIT), `src/main/resources/data.sql` (casos PAY-AX91, PAY-BX17, PAY-CQ22, PAY-DK44) y `application.properties` (H2 en `MODE=Oracle`, scripts al arrancar, consola en `/h2-console`) | Tipos de Oracle (`VARCHAR2`, `NUMBER`), restricciones con nombre y `CHECK` para los estados. Se añadieron índices por `payment_id` y secuencias que empiezan en 100 (no pedidos). Fechas relativas al arranque (`LOCALTIMESTAMP - INTERVAL`) para que siempre caigan en las últimas 24 h. `ddl-auto=none` para que Hibernate no toque el esquema. Verificado con `mvnw test` y una consulta en H2 |
| 2026-10-23 | Crear en el README un apartado para registrar las ayudas de IA | Esta sección | Cada ayuda nueva se añade como una fila a esta tabla |
