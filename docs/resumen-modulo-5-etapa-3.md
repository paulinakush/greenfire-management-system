# Resumen del módulo 5 de la etapa 3: ventas presenciales

## 1. Qué se hizo

- **Paquete nuevo `ventas`:** entidades `Venta`, `DetalleVenta`, `DevolucionVenta` y `DetalleDevolucionVenta`, sus repositorios, `CalculoVenta` (precio, IVA, descuento y margen), `VentaService`, `VentaController` y los DTOs.
- **Modelo:** `docs/modelo-datos.dbml` se actualizó antes de la migración:
    - `ambitos.descuento_maximo_empleado`.
    - `ventas` con `fecha_cobro` y `porcentaje_descuento`.
    - `detalle_ventas` con ubicación, precio de lista y alícuota.
    - Tablas nuevas `devoluciones_venta` y `detalle_devoluciones_venta`.
    - `movimientos_caja.venta_id`, con sus referencias.
- **Migración nueva:** `V14__ventas.sql`. No se tocó ninguna anterior.
- **Cambios en otros módulos:**
    - **Stock:** `RegistroStock` suma `registrarVenta` (movimientos `venta`, `anulacion` y `devolucion` con `venta_id`) y `verificarSalidas`, que bloquea las filas de stock en orden fijo y responde 409 si algún renglón no alcanza. `registrar` ya no acepta tipos de venta.
    - **Caja:** `CajaService.registrarCobro` recibe el `ventaId`, que queda en el movimiento y en su auditoría.
    - **Ámbitos:** `GET` y `PUT /api/ambito/condiciones-venta` para ver y editar el descuento máximo del empleado. Edita solo el dueño de local, con auditoría (`editar_condiciones_venta`, antes y después).
    - **`CLAUDE.md`:** suma el paquete `ventas` y una sección con las decisiones de ventas.

## 2. Lo que garantiza la base (V14)

- **Ventas:**
    - Nacen confirmadas.
    - El cliente es del mismo ámbito y nunca uno que representa a un local.
    - En un local, la venta se cobra en la caja abierta de ese local (salvo cuenta corriente) y no lleva fecha de cobro.
    - En la fábrica, siempre lleva cliente y no pasa por caja. Lleva fecha de cobro, salvo en cuenta corriente.
    - El comprobante es obligatorio, uno por venta: un `comprobante_venta` emitido, del mismo ámbito, con el mismo cliente y el mismo total.
    - Total = subtotal − descuento.
    - Al confirmar la transacción (constraint trigger diferido), la venta tiene al menos un renglón, el total es la suma de los subtotales y el subtotal, la de los subtotales de lista.
- **Estados:**
    - Solo se pasa de `confirmada` a `anulada`, una vez, con usuario, fecha y motivo.
    - Ningún otro campo cambia.
    - Una venta con devoluciones no se anula.
    - Las ventas no se borran ni se truncan.
- **Renglones (`detalle_ventas`):**
    - Solo se insertan junto con su venta, en la misma transacción (marca local a la transacción, como en entregas), y no cambian.
    - La ubicación es del ámbito de la venta.
    - Nunca insumos, y cantidades enteras si el artículo se mide en unidades.
    - Precio unitario ≤ precio de lista, y subtotal = cantidad × precio unitario redondeado a 2 decimales.
- **Devoluciones:**
    - Son de una venta confirmada.
    - Llevan una nota de crédito emitida por el mismo total, asociada al comprobante de la venta.
    - Si pasan por caja, la caja está abierta en el mismo local.
    - Sus renglones se cargan con ella, son de la misma venta y suman su total.
    - Lo devuelto de un renglón nunca supera lo vendido: el trigger bloquea el renglón de la venta.
- **Movimientos de stock:**
    - Nueva columna `venta_id`, con FK.
    - `venta` exige `venta_id`; `entrega_salida` y `entrega_recepcion`, `entrega_id`.
    - `anulacion` y `devolucion` exigen uno de los dos, nunca ambos. Los demás tipos no llevan ninguno.
    - El movimiento de venta sale una sola vez, por lo vendido, al registrar la venta.
    - La anulación (con la venta anulada) y la devolución (con la venta confirmada) son positivas. Sumadas, no superan lo vendido.
- **Movimientos de caja:** `venta_id` solo en movimientos de origen venta o devolución, de una venta del mismo local. El cobro de la venta va a la caja con la que se registró.
- **Descuento máximo del empleado:** un porcentaje entre 0 y 100.

## 3. Cálculo

- **Precio:**
    - En un local, de la lista del cliente si tiene, si no de la predeterminada del local.
    - En la fábrica, de la lista mayorista del cliente.
    - Si la entidad del ámbito discrimina IVA y la lista es sin IVA, se suma el IVA del artículo.
- **Descuento:** porcentaje por venta, aplicado al precio unitario y redondeado a 2 decimales por renglón. El comprobante se emite con esos precios finales, así que su total es exactamente el de la venta. Un test lo comprueba con IVA y descuento.
- **Lo que se copia por renglón:** precio de lista, precio final, alícuota del artículo y costo unitario del ámbito al vender.
- **Margen (solo para los dueños):** sobre el precio neto de IVA, por renglón y por venta. Si a algún renglón le falta el costo, el costo total y el margen de la venta quedan vacíos.

## 4. Operaciones

- **Registrar venta (`POST /api/ventas`):** todo en una transacción. Pasos, en orden:
    1. Validaciones.
    2. Caja abierta del local (`exigirAbierta`, solo en locales).
    3. Verificación y bloqueo del stock.
    4. Comprobante X (`EmisorComprobantes`).
    5. Venta y renglones.
    6. Movimientos de stock `venta`.
    7. Cobro en la caja (`registrarCobro`, si el total es mayor que 0).
    8. Auditoría.
  
  Si un renglón no alcanza, 409 y no queda nada: ni venta, ni comprobante, ni número consumido, ni movimientos.
- **Sin ubicación,** se usa "Principal". Se puede indicar otra del mismo ámbito; una de otro ámbito, 404.
- **Medios de pago:** efectivo, transferencia y billetera virtual exigen caja abierta en el local. En la fábrica no hay caja. La cuenta corriente responde 409 con "La venta en cuenta corriente se habilita con las cuentas corrientes (módulo 7)."
- **Fecha de cobro en la fábrica:** si no se manda, es hoy. No puede ser futura.
- **Límites (400 con mensaje claro):** hasta 100 renglones, cantidad mayor que 0 y hasta 10000 por renglón, cantidades enteras en artículos por unidad, sin repetir artículo y ubicación.
- **Anular (`POST /api/ventas/{id}/anular`):**
    - Solo un dueño, con motivo, sobre una venta confirmada y sin devoluciones.
    - Bloquea la venta, devuelve todo el stock (movimientos `anulacion`) y anula el comprobante.
    - Si la venta se cobró en la caja, egresa el dinero de la caja abierta en ese momento. Sin caja abierta, 409: "No hay una caja abierta en este local: abrila para devolver el dinero de la anulación.".
- **Devolución parcial (`POST /api/ventas/{id}/devoluciones`):**
    - Solo un dueño, con motivo, por renglones y cantidades.
    - Pasarse de lo vendido menos lo ya devuelto responde 409 ("se pueden devolver hasta …").
    - Genera movimientos `devolucion`, una nota de crédito X asociada al comprobante original y, si la venta pasó por caja, el egreso del dinero.
- **Concurrencia:** la anulación y la devolución bloquean la venta (`SELECT ... FOR UPDATE`), así que se ordenan. El orden de bloqueos es siempre venta, sesión de caja, filas de stock y numeración, para no trabar.

## 5. Permisos y lectura

- **Local:** venden el empleado y el dueño, con cliente opcional.
- **Fábrica:** vende solo el dueño de fábrica, siempre a un mayorista con lista. Un cliente que representa a un local responde 409 (eso son entregas).
- **Anulación y devolución:** solo dueños. El empleado recibe 403.
- **Administrador técnico:** 403. **Sin sesión:** 401. **Venta de otro ámbito:** 404, también al anular o devolver.
- **Lectura:**
    - `GET /api/ventas`, con filtros `desde`, `hasta`, `clienteId`, `medio`, `estado`, `pagina` y `tamano`.
    - `GET /api/ventas/{id}`.
    - El empleado ve solo las ventas del día de su local, con un DTO propio (`VentaEmpleadoResponse`) sin costos ni márgenes. Una venta de otro día le responde 404.
    - Los dueños ven todo, con costo y margen. La fábrica ve solo sus ventas.
- **Historial del cliente:** `GET /api/clientes/{id}/historial` devuelve sus ventas, paginadas, con los mismos permisos que el cliente. Los pedidos se suman en el módulo 9.

## 6. Auditoría

- **Se registran:** `registrar`, `anular` y `devolver` (entidad `venta`), además del movimiento de caja de cada cobro o devolución.
- **Siempre con el ámbito de la venta como afectado.**
- **En la misma transacción:** si la auditoría falla, no queda nada (probado en el test de la venta que falla a mitad de camino).

## 7. Base de desarrollo

- **Respaldo antes de migrar:** `greenfire-20261111-181151.dump.enc`.
- **V14 aplicada** al arrancar la aplicación. Las cantidades de filas quedaron iguales, salvo el historial de Flyway (13 a 14).
- **Recorrido con curl:**
    - **Preparación:** el administrador técnico reactivó a los usuarios de prueba del módulo 4 (`pruebacaja.dueno` y `pruebacaja.empleado`, Local Ruta 1) y creó `pruebaventas.fabrica` (dueño de fábrica).
    - **Datos de prueba:** un sustrato de la fábrica (costo 3000, lista mayorista 5000 sin IVA) y una maceta de reventa del local (costo 900), con una lista predeterminada del local sin IVA (maceta 1500, sustrato 8000) y stock en las dos ubicaciones. Descuento máximo del empleado: 10 %.
    - **Venta en efectivo en el local:**
        - El empleado abrió la caja y vendió 2 macetas y 1 sustrato con 5 % de descuento: 201.
        - Precios con IVA 1815 y 9680. Unitarios con descuento 1724,25 y 9196. Total 12644,50, igual al del comprobante X 00001-00000001.
        - La respuesta del empleado no trae costo ni margen. Un 15 % de descuento le respondió 403 con el tope.
        - El dueño vio la misma venta con costo (900) y margen (36,84 %) de la maceta. El sustrato aparece sin costo porque el local todavía no recibió ninguna entrega.
    - **Venta de la fábrica al mayorista "Vivero de Prueba SRL":** 10 sustratos por transferencia, 201. Total 60500, sin caja, con fecha de cobro de hoy, costo 30000 y margen 40 %.
    - **Devolución parcial:**
        - El empleado recibió 403.
        - El dueño devolvió 1 maceta: 201, con la nota de crédito X 00001-00000001 por 1724,25.
        - Devolver 2 más respondió 409: "se vendieron 2 y ya se devolvieron 1: se pueden devolver hasta 1".
    - **Anulación:**
        - El empleado vendió 3 macetas con billetera virtual (5445). Al intentar anular, recibió 403.
        - El dueño la anuló: la venta quedó anulada, el comprobante anulado y el stock devuelto.
    - **Lista del empleado:** sus 2 ventas del día, sin costos ni márgenes.
    - **Caja:** ventas en efectivo 12644,50, devoluciones 1724,25, esperado 20920,25. Se cerró sin diferencia. Los cobros con billetera y su devolución se compensan.
    - **Stock y auditoría:**
        - Los movimientos `venta`, `devolucion` y `anulacion` quedaron con su `venta_id`.
        - La auditoría quedó con `registrar`, `devolver` y `anular`, con ámbito actuante y afectado 4 (o 1 para la venta de la fábrica).
- **Al terminar:** los tres usuarios de prueba quedaron desactivados. Las ventas, la caja cerrada y los artículos de prueba quedan en la base de desarrollo.

## 8. Tests

- **Suite completa:** 444 tests, todos pasan. El módulo suma 43.
- **`VentaApiTest` (19):**
    - Venta en efectivo con stock, comprobante y caja.
    - Vista del dueño y del empleado.
    - Total igual al del comprobante con IVA y descuento.
    - Lista del cliente.
    - Tope de descuento del empleado.
    - Sin stock, sin caja o cuenta corriente.
    - Límites de cantidades y renglones, y ubicación Principal u otra.
    - Ventas de la fábrica a mayoristas y nunca a un local.
    - Anulación, y anulación o devolución sin caja abierta.
    - Devolución parcial.
    - Ventas del día del empleado.
    - Otro ámbito, administrador técnico y sin sesión.
    - Historial del cliente y auditoría.
    - Una venta de 10 renglones en menos de 2 segundos.
- **`VentaMigracionTest` (19):** un test por cada regla de la base, incluida la referencia de cada tipo de movimiento de stock (`venta_id` y `entrega_id`).
- **`VentaConcurrenciaTest` (5), con transacciones reales:**
    - Dos ventas del último artículo: una 201 y la otra 409.
    - Una venta que falla a mitad de camino (falla la auditoría): 500, sin venta, comprobante, número consumido ni movimientos.
    - Anulación contra devolución, en los dos órdenes: la que llega segunda espera el bloqueo. Si gana la devolución, la anulación responde 409.
    - Dos devoluciones simultáneas no devuelven más de lo vendido.
- **`EntregaMigracionTest`:** se actualizó el nombre de la restricción de referencias (`movimientos_stock_referencia_segun_tipo`).

## 9. Decisiones a revisar

- **El descuento se aplica al precio unitario** (redondeado por renglón), no al total. Así el comprobante coincide con la venta, pero el descuento efectivo puede diferir en centavos del porcentaje sobre el total.
- **Una venta con devoluciones no se anula:** para deshacerla, se devuelve el resto.
- **En la fábrica, la lista mayorista del cliente es obligatoria:** si el cliente no tiene lista, 409. No se usa la predeterminada de la fábrica.
- **La devolución de dinero sale de la caja abierta en ese momento,** que puede no ser la de la venta. El monto es el total de la nota de crédito.
- **Con cantidades fraccionadas devueltas en partes,** la suma de las notas de crédito puede diferir en un centavo del renglón original.
- **Sin costo, sin margen:** el margen de la venta queda vacío si falta el costo de algún renglón (por ejemplo, productos de la fábrica sin entrega recibida).
- **"Las del día" del empleado** son las ventas de hoy, en hora de Argentina.
- **Límites elegidos:** 100 renglones por venta y 10000 de cantidad por renglón.
- **La base no exige `venta_id` en los movimientos de caja de origen venta,** para no romper los tests de la caja que usan el punto de enganche sin venta. El servicio siempre lo manda.

## 10. Pendiente

- **Módulo 7:** venta en cuenta corriente y `cobranza_id` en la caja.
- **Módulo 9:** reservas de los pedidos (el disponible hoy es la cantidad actual), `pedido_id` en la venta y los pedidos en el historial del cliente.
- **Etapa 4:** costo promedio ponderado.
- **Visibilidad de comprobantes por ámbito emisor:** sigue anotada en el plan, para arreglar antes de cargar datos reales.
- **No se empezó el módulo 6.**
