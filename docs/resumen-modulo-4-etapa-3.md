# Resumen del módulo 4 de la etapa 3: caja

## 1. Qué se hizo

- **Paquete nuevo `caja`:** entidades `SesionCaja` y `MovimientoCaja`, sus repositorios, `CierreCaja` (cálculo de totales), `CajaService`, `CajaController` y los DTOs.
- **Modelo:** `docs/modelo-datos.dbml` se actualizó antes de la migración, con los enums `origen_movimiento_caja` y `rubro_caja`, los campos nuevos de `sesiones_caja` y de `movimientos_caja`, y sus referencias.
- **Migración nueva:** `V13__caja.sql`. No se tocó ninguna anterior.
- **`ManejadorErrores`:** el índice único de la caja abierta responde 409 con "Ya hay una caja abierta en este local.".
- **`CLAUDE.md`:** suma el paquete `caja` y una sección corta con las decisiones de caja.
- **`docs/plan-etapa-3.md`:** en "Para revisar" quedó anotado que la visibilidad de comprobantes tiene que filtrarse por el ámbito que los emitió (la regla 3 prevalece sobre "la entidad de su ámbito"), y que hay que arreglarlo antes de cargar datos reales.

## 2. Modelo y migración V13

- **`sesiones_caja`:**
    - Apertura: local, usuario, fecha y saldo inicial (no negativo).
    - Cierre: usuario, fecha, total en efectivo, en transferencia y en billetera virtual, total general, ingresos y egresos manuales en efectivo, efectivo esperado, efectivo contado, diferencia y observaciones.
    - Corrección: quién, cuándo y con qué motivo.
- **`movimientos_caja`:**
    - Origen: `manual`, `venta`, `cobranza` o `devolucion`.
    - Tipo: ingreso o egreso.
    - Rubro: `gasto`, `retiro`, `aporte`, `pago_fabrica` u `otro`, con `CHECK`. Lo llevan solo los manuales.
    - Medio: efectivo, transferencia o billetera virtual. La cuenta corriente no pasa por la caja.
    - Monto positivo, observación (obligatoria en los manuales) y `corrige_movimiento_id` para corregir un error.
- **Lo que garantiza la base, aunque se escriba por fuera de la aplicación:**
    - Solo los locales tienen caja: la fábrica no.
    - Una sola sesión abierta por local, con el índice único parcial `uq_sesiones_caja_abierta`.
    - Una sesión nace abierta, y los datos de su apertura no cambian nunca.
    - Abierta solo pasa a cerrada, y el trigger verifica que los totales del cierre sean los de sus movimientos (función `caja_totales`). Un `CHECK` verifica que el esperado y la diferencia cuadren.
    - Cerrada, solo admite la corrección: cambian el efectivo contado y la diferencia, con quién, cuándo y motivo. Cualquier otro cambio se rechaza.
    - Las sesiones no se borran ni se truncan.
    - Los movimientos solo se insertan, y solo en una sesión abierta. No se modifican, no se borran ni se truncan, igual que los movimientos de stock.
    - El tipo sigue al rubro (gasto, retiro y pago a la fábrica son egresos; el aporte, ingreso; "otro" puede ser cualquiera) y al origen (ventas y cobranzas, ingresos; devoluciones, egresos).
    - La corrección de un movimiento es manual, de la misma sesión, del mismo rubro y medio y del tipo contrario. No corrige otra corrección, y la suma de las correcciones no supera al original.

## 3. Cálculo de la caja

- **Efectivo esperado** = saldo inicial + ventas en efectivo + cobranzas en efectivo + ingresos manuales en efectivo - egresos manuales en efectivo - devoluciones en efectivo.
- **Transferencias y billeteras virtuales** se totalizan aparte (cobros netos de devoluciones) y no suman al efectivo.
- **Movimientos manuales por transferencia** (por ejemplo, un pago a la fábrica por transferencia) quedan registrados, pero no tocan el efectivo ni los totales de cobro.
- **El mismo cálculo** está en `CierreCaja` (Java) y en `caja_totales` (SQL). El cierre se verifica contra la función.

## 4. Operaciones

- **Apertura:** con saldo inicial. Si ya hay una caja abierta, 409. Dos aperturas simultáneas: una responde 201 y la otra 409, por el índice único.
- **Movimiento manual:** bloquea la sesión abierta (`SELECT ... FOR UPDATE`) y lo registra. Sin caja abierta, 409.
- **Corrección de un movimiento:** se manda `corrigeMovimientoId` y el monto. El tipo, el rubro y el medio salen del original.
- **Cierre:** bloquea la sesión, calcula los totales, guarda el contado y la diferencia, y registra la auditoría, todo en una transacción. Si algo falla, la caja sigue abierta.
- **Cierre y movimiento simultáneos:** se ordenan por el bloqueo de la sesión. Si el movimiento llega primero, el cierre lo incluye. Si llega durante el cierre, espera y responde 409 porque la caja ya está cerrada. El trigger de movimientos toma además un bloqueo compartido de la sesión, por si se escribe por fuera de la aplicación.
- **Corrección del cierre:** solo el dueño de local, con motivo obligatorio, sobre una caja cerrada. Cambia el efectivo contado y recalcula la diferencia. Si el valor es el mismo, 409. Se puede corregir más de una vez, y cada corrección queda en auditoría.
- **Olvidada:** una caja abierta hace más de 24 horas aparece con `olvidada: true` para el dueño, sin cerrarse sola. Hasta que alguien la cierre, no se puede abrir otra.

## 5. Puntos de enganche para ventas y cobranzas (módulos 5 y 7)

- **`CajaService.exigirAbierta(ambitoId)`:** devuelve la sesión abierta del local, bloqueada. Si no hay, 409 "No hay una caja abierta en este local.".
- **`CajaService.registrarCobro(sesion, origen, medio, monto, usuario, observacion)`:** registra el movimiento de una venta, una cobranza o una devolución, con su auditoría. Rechaza los manuales, la cuenta corriente y los montos no positivos.
- **Los dos exigen una transacción en curso (MANDATORY),** así el cobro se revierte con la venta si algo falla.
- **Pendiente para los módulos 5 y 7:** sumar a `movimientos_caja` la referencia a la venta o la cobranza (`venta_id`, `cobranza_id`), cuando existan esas tablas.

## 6. Endpoints y permisos

- **Endpoints:**
    - `POST /api/caja/abrir`: abre la caja del local de la sesión (201).
    - `GET /api/caja/actual`: la caja abierta (200) o nada (204).
    - `POST /api/caja/movimientos`: movimiento manual o corrección de un movimiento (201).
    - `POST /api/caja/cerrar`: cierra la caja abierta con el efectivo contado.
    - `GET /api/caja/sesiones`: historial, con `desde`, `hasta`, `pagina` y `tamano`.
    - `GET /api/caja/sesiones/{id}`: una sesión con sus movimientos.
    - `POST /api/caja/sesiones/{id}/correccion`: corrección del cierre.
- **Empleado de local:**
    - Abre, cierra y registra gastos, aportes y "otro". No registra retiros ni pagos a la fábrica (403), y no corrige cierres (403).
    - Ve la caja abierta y las sesiones del día. Una sesión de otro día responde 404.
    - Caja a ciegas: con la caja abierta, su respuesta no trae totales, esperado ni diferencia. Después del cierre ve el esperado, el contado y la diferencia.
    - En su lista no aparecen los movimientos de ventas ni los pagos a la fábrica.
- **Dueño de local:** hace todo, ve los totales en todo momento y tiene el historial completo, con la marca de olvidada.
- **Dueño de fábrica:** con el ámbito de la fábrica, 403 (la fábrica no tiene caja). Si entra al Local La Rioja con su perfil de dueño de local, tiene la caja de ese local como cualquier dueño.
- **Administrador técnico:** 403. **Sin sesión:** 401.
- **Otro ámbito:** pedir o corregir una sesión de otro local responde 404, y el historial no la muestra.

## 7. Auditoría

- **Se registran:** la apertura (`abrir`), cada movimiento (`registrar_movimiento`, también los de ventas y cobranzas), el cierre (`cerrar`, con los totales, el esperado, el contado y la diferencia) y la corrección (`corregir_cierre`, con el contado y la diferencia antes y después, y el motivo).
- **Siempre con el local como ámbito afectado.**
- **En la misma transacción:** si la auditoría falla, la operación no se hace (probado con el cierre).

## 8. Base de desarrollo

- **Respaldo antes de migrar:** `greenfire-20261111-172453.dump.enc`, verificado (26 tablas con datos).
- **V13 aplicada** al arrancar la aplicación. Las cantidades de filas de todas las tablas quedaron iguales, salvo el historial de Flyway (12 a 13).
- **Recorrido con curl, en Local Ruta 1:**
    - El administrador técnico creó un dueño de prueba (`pruebacaja.dueno`), y este creó un empleado (`pruebacaja.empleado`).
    - El administrador técnico pidió la caja: 403.
    - El empleado abrió la caja con 5000 (201) y registró un gasto de 1200 en efectivo (201). Un retiro le respondió 403.
    - Con la caja abierta, la respuesta del empleado no trajo totales ni esperado. La del dueño mostró el esperado: 3800.
    - El empleado cerró contando 3750. La respuesta trajo el esperado (3800) y la diferencia (-50).
    - En las sesiones del día, el empleado vio el cierre con la diferencia.
    - La auditoría quedó con `abrir`, `registrar_movimiento` y `cerrar`, con ámbito actuante y afectado 4 (Local Ruta 1).
- **Al terminar:** los dos usuarios de prueba quedaron desactivados. La sesión de caja cerrada queda en la base de desarrollo.

## 9. Tests

- **Suite completa:** 401 tests, todos pasan. El módulo suma 27.
- **`CajaApiTest` (14):** recorrido completo con caja a ciegas, vista del dueño con el desglose, una sola caja abierta, validaciones de los movimientos manuales, corrección de un movimiento, corrección del cierre con auditoría, auditoría de todas las operaciones, puntos de enganche, permisos del empleado, la fábrica y el dueño de los dos ámbitos, administrador técnico y sin sesión, otro local, sesiones del día y historial, y caja olvidada.
- **`CajaMigracionTest` (8):** lo que garantiza la base: solo locales, una abierta, apertura inmutable, totales del cierre, solo la corrección después del cierre, movimientos solo de inserción, rubro, tipo, medio y observación, y reglas de la corrección de un movimiento.
- **`CajaConcurrenciaTest` (5), con transacciones reales:**
    - Cinco aperturas simultáneas: una 201 y cuatro 409.
    - Un cierre que falla a mitad de camino (falla la auditoría): responde 500, la caja queda abierta, sin totales, y después se cierra bien.
    - Movimiento que llega durante el cierre: espera y responde 409. El cierre no lo incluye.
    - Cierre que llega durante un movimiento: espera y lo incluye.
    - Cierre y movimiento largados a la vez, cinco veces: siempre coherente (o el movimiento entró y el cierre lo cuenta, o respondió 409 y no quedó).
    - Se corrieron tres veces más por separado, sin fallas.

## 10. Decisiones a revisar

- **El empleado no ve los pagos a la fábrica ni los movimientos de ventas** en su lista de la caja. Los pagos a la fábrica son cuenta corriente con la fábrica (regla 2); las ventas las ve en las ventas.
- **La observación es obligatoria en los movimientos manuales.**
- **El rubro "otro" admite ingreso o egreso,** así que hay que elegir el tipo. Los demás rubros tienen el tipo fijo.
- **La corrección del cierre se puede hacer más de una vez.** Cada una queda en auditoría con el valor anterior y el nuevo.
- **"Las del día"** son las sesiones abiertas hoy (hora de Argentina), más la abierta aunque sea de otro día.
- **El historial del dueño se filtra por fecha de apertura.**

## 11. Pendiente

- **Módulo 5 (ventas) y 7 (cobranzas):** usar `exigirAbierta` y `registrarCobro`, y sumar la referencia a la venta o la cobranza en `movimientos_caja`.
- **Visibilidad de comprobantes por ámbito emisor:** anotada en el plan, para arreglar antes de cargar datos reales.
- **No se empezó el módulo 5.**
