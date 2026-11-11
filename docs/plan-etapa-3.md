# Plan de la etapa 3: clientes, pedidos, ventas, caja y cuenta corriente fábrica-local

Basado en `CLAUDE.md`, `docs/complemento.md`, `docs/modelo-datos.dbml` y `docs/plan-etapa-2.md` (en especial "Para revisar en la etapa 3"). Aprobado con los ajustes de la dueña (sección 0, clientes y ventas mayoristas de la fábrica, pago web) y los técnicos. Se trabaja de a un módulo por vez, con su resumen y sus tests antes de pasar al siguiente.

## 0. Estructura del negocio que asume el plan

- **Fábrica:** produce y distribuye el sustrato.
  - No vende al público minorista: de eso se encargan los locales de GREENFIRE.
  - Le vende a los locales y, con su Línea Jardinería, vende al por mayor a paisajistas, viveros y growshops, que la contactan para coordinar la venta.
  - Solo la venden los dos dueños de la fábrica.
- **Tres locales con el nombre GREENFIRE:**
  - El central, Local La Rioja, con los mismos dueños que la fábrica.
  - Mitre y Ruta 1, cada uno con dueños distintos de los de la fábrica.
- **Cuatro entidades fiscales:** cada una con su CUIT, su condición frente al IVA y sus puntos de venta.
- **Entregas:** la entrega de la fábrica a un local es una venta con deuda. La fábrica es proveedora de cada local, y cada local es cliente de la fábrica.
- **Privacidad entre ámbitos:**
  - Nadie ve datos de otro local.
  - La fábrica no ve la caja ni las ventas de los locales.
  - Los dueños de la fábrica ven el local central solo cuando entran con ese ámbito.

## 1. Tablas del modelo que entran y cambios al modelo

Todo cambio va primero a `docs/modelo-datos.dbml` y después a una migración nueva (V11 en adelante). Ninguna migración aplicada se modifica.

### Tablas que entran

- **Clientes:** `clientes`.
- **Comprobantes:** `comprobantes` y `detalle_comprobante`, solo no fiscales (letra X). Los fiscales quedan para la etapa 6.
- **Caja:** `sesiones_caja` y `movimientos_caja`.
- **Ventas:** `ventas` y `detalle_ventas`.
- **Cuentas corrientes:** `movimientos_cuenta_corriente`, `cobranzas` y `detalle_cobranza`.
- **Pedidos online:** `pedidos`, `detalle_pedidos`, `pedido_historial` y `reservas_stock`.
- **Columnas pendientes de la etapa 2:**
  - `movimientos_stock.venta_id` y `movimientos_stock.pedido_id`.
  - `entregas.remito_id` y `entregas.nota_credito_id`.
  - `entregas.factura_id` se agrega ahora, pero queda en null hasta la etapa 6.

### Quedan afuera

- `proveedores`, `compras`, `pagos_proveedor` y sus detalles: son de la etapa 4.
  - En esta etapa, el lado del local de la cuenta con la fábrica se lee de la misma cuenta corriente de la fábrica (ver punto 5), sin duplicar movimientos.
  - Cuando lleguen las compras, `compras.entrega_id` y `pagos_proveedor.cobranza_id` se enlazan con lo que ya existe.
- `promociones` y los comprobantes fiscales con ARCA: son de la etapa 6.

### Cambios al modelo

- **`clientes`:**
  - **Suma `ambito_id`:** el ámbito dueño del cliente. Hoy los clientes no tienen ámbito, y la regla 3 de `CLAUDE.md` exige que un local no vea los clientes de otro.
  - **Unicidad del documento:** el documento es único dentro del ámbito (tipo y número), con un índice parcial donde el documento no es null.
  - **Clientes de la fábrica:** los locales y los mayoristas de la Línea Jardinería.
    - El cliente que representa a un local pertenece a la fábrica, y `(ambito_id, ambito_representado_id)` es único.
    - Los mayoristas son clientes comunes de la fábrica, sin `ambito_representado_id`.
  - **Sale `password_hash`:** las cuentas web de los clientes van en una tabla aparte (ver punto 8.1).
  - **Suma `cuenta_corriente_habilitada`** (boolean) y **`limite_credito`** (opcional). Se le puede vender a cuenta corriente solo a un cliente habilitado, y sin pasar el límite.
- **`ambitos`:** suma tres columnas, que solo tienen sentido en los locales.
  - `datos_transferencia`: alias y CBU, que se muestran al cliente cuando el pedido se confirma (punto 7).
  - `horas_vencimiento_pedido`: 48 por defecto.
  - `descuento_maximo_empleado`: un porcentaje, 0 por defecto (punto 8.6).
  - **Quién las edita:** el dueño de local, en su local, con auditoría del valor anterior y el nuevo (con `Cambios`). El administrador técnico no, porque son datos comerciales.
  - **Cuándo entran:** cada una con el primer módulo que la usa. El descuento, con las ventas; los datos de transferencia y las horas, con los pedidos.
- **`punto_venta_comprobantes`:** suma `ultimo_numero`, para numerar sin huecos con un bloqueo de fila (punto 6).
- **`tipo_comprobante`:** suma `comprobante_venta`, el comprobante simple de una venta presencial, letra X (punto 8.3).
- **`ventas.comprobante_id`:** cambia la nota del modelo.
  - Hoy dice "null si la venta no tiene comprobante fiscal".
  - Pasa a ser el comprobante de la venta, que puede ser X o fiscal. Si es fiscal se sabe por su tipo y su letra.
- **`detalle_ventas` y `detalle_pedidos`:** suman `alicuota_iva`, copiada del artículo al vender, para facturar en la etapa 6 sin depender del valor actual.
- **`stock_articulos`:** suma `cantidad_reservada`, un caché de las reservas activas que mantiene un trigger, igual que `cantidad_actual` (punto 4).
- **`cobranzas`:** suma `estado` (`informada`, `confirmada` o `rechazada`) y `informada_por_ambito_id`, para que el local informe un pago a la fábrica y la fábrica lo confirme (punto 5).
- **`pedidos`:**
  - **Suma `codigo_seguimiento`:** un valor aleatorio y único, para que el cliente siga su pedido sin cuenta.
  - **Suma los datos de contacto del cliente invitado:** nombre, teléfono y email. Se usan si el pedido no tiene `cliente_id`.
  - **Suma `motivo`:** obligatorio al rechazar o cancelar.
- **`sesiones_caja`:** suma `corregida_por_usuario_id`, `corregida_at` y `motivo_correccion`, para la corrección autorizada de un cierre (punto 3, módulo 4).
- **`movimientos_caja`:** `rubro` pasa a `varchar` con CHECK: `gasto`, `retiro`, `aporte`, `pago_fabrica` y `otro`.
- **Restricciones de `movimientos_stock`:**
  - El tipo `venta` exige `venta_id`.
  - `anulacion` y `devolucion` exigen `entrega_id` o `venta_id`, uno solo.
- **`tipo_movimiento_stock.entrega_pedido`:**
  - Con la propuesta del punto 7 no se usa, porque el pedido se descuenta con la venta que lo cobra.
  - Propuesta: dejarlo en el enum, sin uso, hasta confirmarlo.

## 2. Módulos, en orden

Cada módulo lleva su migración, sus tests de acceso (qué perfil puede hacer qué y que nadie vea datos de otro ámbito) y su resumen. Al terminar cada uno se puede probar algo de punta a punta.

1. **Clientes.**
   - Se prueba: el dueño y el empleado dan de alta y buscan clientes de su local, y no ven los de otro.
   - La fábrica tiene un cliente por cada local, creado por la base, y el dueño de fábrica da de alta a sus mayoristas.
2. **Backups.**
   - Se prueba: el backup diario se genera y una restauración en una base vacía levanta la app con los datos.
   - Tiene que estar listo antes de cargar datos reales (punto 8.13).
   - La carga de datos de prueba de la base de desarrollo suma un punto de venta X activo en cada ámbito, que necesitan los módulos siguientes.
3. **Comprobantes no fiscales y numeración.**
   - Se prueba: dos comprobantes emitidos a la vez en el mismo punto de venta reciben números seguidos, sin huecos ni repetidos, y cada entidad ve solo los suyos.
   - Se puede ver la vista imprimible.
4. **Caja.**
   - Se prueba: un empleado abre la caja con saldo inicial, registra un gasto y la cierra. El sistema calcula el efectivo esperado y la diferencia.
   - No se pueden abrir dos cajas en el mismo local.
5. **Ventas.**
   - Se prueba: una venta de mostrador con caja abierta descuenta el stock, emite el comprobante X y suma al cierre de caja.
   - Sin stock disponible, falla con 409.
   - El dueño la anula: el stock vuelve, la caja la descuenta y queda en auditoría.
   - El dueño de fábrica registra una venta a un mayorista, con la lista mayorista y sin caja.
6. **Tarea aparte: conectar `privado/sucursal.html` a ventas y caja.**
   - Se prueba en el navegador: el empleado abre la caja, vende y cierra, y el dueño ve el cierre.
   - El resto de la pantalla sigue con los datos de prueba hasta que se conecten sus módulos.
7. **Cuentas corrientes de clientes y cobranzas.**
   - Se prueba: una venta a cuenta corriente aumenta el saldo del cliente.
   - Una cobranza lo baja, emite un recibo y se imputa a la deuda más vieja.
   - Si es en efectivo y la cobra un local, suma a la caja.
   - El dueño de fábrica opera la cuenta de sus mayoristas.
8. **Cuenta corriente entre la fábrica y los locales.**
   - Se prueba: una entrega recibida genera la deuda del local, con remito.
   - La fábrica registra una cobranza.
   - El local informa un pago y la fábrica lo confirma.
   - Cada local ve solo su saldo y la fábrica ve el de todos, con los vencidos.
   - Incluye las tres notas del punto 9.
9. **Pedidos online.**
   - Requisito antes de publicar: el servidor intermedio confiable configurado para el límite por IP (ver el módulo 9 en el punto 3).
   - Se prueba: un visitante hace un pedido sin cuenta, el local lo confirma y se reserva el stock.
   - El catálogo público muestra el disponible ya descontado.
   - El pedido vence sin pago y libera la reserva.
   - Si se cobra, se convierte en venta.

## 3. Clases, endpoints y permisos por módulo

Reglas generales, iguales a las de las etapas 1 y 2:

- El ámbito sale siempre de la sesión. Pedir por id un registro de otro ámbito devuelve 404.
- Cada servicio valida el acceso al principio con `Acceso`.
- Toda operación sensible queda en auditoría con el ámbito afectado: venta, anulación, devolución, apertura y cierre de caja, corrección de cierre, cobranza, confirmación, rechazo, reasignación y vencimiento de pedidos, y ajuste de cuenta corriente.
- El administrador técnico no ve datos comerciales. Su único papel en esta etapa es configurar los puntos de venta X de cada ámbito.
- El empleado nunca recibe costos, márgenes ni la cuenta con la fábrica. Se usan DTOs separados, no campos en null.
- Los importes son `numeric(14,2)`.
- Ventas, comprobantes, movimientos de cuenta corriente, movimientos de caja y cobranzas no se borran:
  - Los movimientos son solo de inserción, con triggers como los de `auditorias` y `movimientos_stock`.
  - Las ventas, los comprobantes y las cobranzas solo cambian de estado por las transiciones válidas, controladas por triggers como los de `entregas`.

### Módulo 1: clientes (paquete `clientes`)

- **Clases:** `Cliente`, `ClienteRepository`, `ClienteService` y `ClienteController`.
  - DTOs: `ClienteResponse` para el dueño, con el límite de crédito, y `ClienteEmpleadoResponse` para el empleado.
- **Endpoints:**
  - `GET /api/clientes?q=&activo=`: busca por nombre, razón social o documento.
  - `GET /api/clientes/{id}`.
  - `GET /api/clientes/{id}/historial`: sus ventas y pedidos.
  - `POST /api/clientes`, `PUT /api/clientes/{id}` y `POST /api/clientes/{id}/activar|desactivar`.
- **Permisos:**
  - **Dueño de local:**
    - Todo sobre los clientes de su local.
    - Es el único que habilita la cuenta corriente y fija el límite, la lista de precios y el plazo de pago.
  - **Empleado:**
    - Busca y da de alta clientes en el mostrador, y edita sus datos de contacto.
    - No desactiva ni cambia condiciones comerciales.
  - **Dueño de fábrica:** administra los clientes de la fábrica.
    - **Los locales:** los crea la base y no se dan de alta a mano. Solo edita su plazo de pago y su lista de precios. No los desactiva mientras el local esté activo.
    - **Los mayoristas** de la Línea Jardinería (paisajistas, viveros y growshops): los da de alta, los edita y los desactiva, con cuenta corriente, límite de crédito, lista de precios y plazo.
- **Reglas:**
  - El cliente de cada local en la fábrica lo crea un trigger al dar de alta un ámbito de tipo local, igual que la ubicación "Principal". La migración los crea para los locales existentes.
  - Nombre o razón social obligatorio.
  - El documento es opcional para el consumidor final de mostrador, pero obligatorio si tiene cuenta corriente.

### Módulo 2: backups (fuera del código de la app)

- **Script:** `pg_dump` diario con retención de 30 días y copia fuera del servidor.
- **Procedimiento de restauración:** documentado en `docs/` y probado.
- **Datos de prueba:** la carga de la base de desarrollo suma un punto de venta activo en cada ámbito, con los tipos X que usan los módulos siguientes. Los datos de prueba no van al repositorio.
- **Alcance:** depende de dónde se aloje el sistema (punto 8.13).

### Módulo 3: comprobantes no fiscales (paquete `comprobantes`)

- **Clases:**
  - Entidades y repositorios: `Comprobante` y `DetalleComprobante`.
  - `NumeradorComprobantes`: toma el siguiente número con `SELECT ... FOR UPDATE` sobre `punto_venta_comprobantes`, en la misma transacción.
  - `EmisorComprobantes`: el punto único para emitir, como `RegistroStock`.
  - `ComprobanteService` y `ComprobanteController`.
- **Endpoints:**
  - Consultar: `GET /api/comprobantes` (filtros: tipo, fechas, cliente) y `GET /api/comprobantes/{id}`.
  - Vista imprimible: `GET /api/comprobantes/{id}/imprimible`, en HTML (punto 8.4).
  - No hay alta directa: los comprobantes los emiten las ventas, las entregas y las cobranzas.
- **Permisos:**
  - Cada dueño ve los comprobantes de la entidad de su ámbito activo.
  - El empleado ve e imprime los de las ventas y cobranzas de su local, sin datos de costo.
  - Nadie ve los de otra entidad.
- **Reglas:**
  - Para emitir, el ámbito necesita un punto de venta activo que admita ese tipo y letra. Si no lo tiene, la operación falla con un mensaje claro para que el administrador técnico lo configure.
  - Datos del emisor y del receptor copiados al emitir.
  - Neto, IVA y total calculados según la condición de la entidad emisora (punto 6).
  - Un comprobante emitido no cambia. Solo pasa a `anulado`, con su operación.

### Módulo 4: caja (paquete `caja`)

- **Clases:** `SesionCaja`, `MovimientoCaja`, `RubroCaja`, `CajaService`, `CierreCaja` (el cálculo) y `CajaController`.
- **Endpoints:**
  - Apertura: `POST /api/caja/abrir`, con el saldo inicial.
  - Caja actual: `GET /api/caja/actual`, con la sesión abierta y sus totales hasta el momento.
  - Movimientos manuales: `POST /api/caja/movimientos`, con tipo, rubro, medio, monto y observación.
  - Cierre: `POST /api/caja/cerrar`, con el efectivo contado y observaciones.
  - Historial: `GET /api/caja/sesiones` y `GET /api/caja/sesiones/{id}`, con el detalle de ventas, cobranzas y movimientos.
  - Corrección: `POST /api/caja/sesiones/{id}/correccion`, con el efectivo contado corregido y el motivo.
- **Permisos:**
  - **Empleado:**
    - Abre, cierra y registra ingresos y gastos.
    - Ve la sesión abierta y las del día.
    - No registra retiros ni corrige cierres.
  - **Dueño de local:** todo, incluido el historial, los retiros y la corrección de un cierre, que queda en auditoría.
  - **Dueño de fábrica:** no ve la caja de ningún local. Ve la del central solo si entra con ese ámbito.
- **Reglas:**
  - Una sola sesión abierta por local, con un índice único parcial.
  - Sin caja abierta no se registran ventas ni cobranzas del local.
  - **Efectivo esperado** = saldo inicial + ventas en efectivo + cobranzas en efectivo + ingresos en efectivo − egresos en efectivo − devoluciones y anulaciones en efectivo.
  - Los cobros por transferencia o billetera virtual se totalizan aparte y no suman al efectivo esperado.
  - Al cerrar se guardan los totales y la diferencia. Un trigger rechaza cualquier cambio posterior, salvo la corrección autorizada, que solo toca el efectivo contado, la diferencia y los datos de la corrección.
  - La fábrica no tiene caja en esta etapa. Sus cobros llevan medio de pago y fecha, sin sesión de caja (punto 8.7).

### Módulo 5: ventas (paquete `ventas`)

- **Clases:**
  - Entidades y repositorios: `Venta`, `DetalleVenta` y `EstadoVenta`.
  - `CalculoVenta`: precio, IVA y descuento.
  - `VentaService` y `VentaController`.
  - `Disponibilidad`, en `stock` (punto 4).
  - DTOs: `VentaResponse` para los dueños, con costo y margen, y `VentaEmpleadoResponse`.
- **Endpoints:**
  - Registrar: `POST /api/ventas`, con renglones (artículo, cantidad y ubicación opcional), cliente (opcional en un local, obligatorio en la fábrica), medio de pago y descuento.
  - Consultar: `GET /api/ventas` (filtros: fechas, cliente, medio, estado) y `GET /api/ventas/{id}`.
  - Anular: `POST /api/ventas/{id}/anular`, con el motivo.
  - Devolución parcial: `POST /api/ventas/{id}/devoluciones`, con los renglones devueltos y el motivo.
- **Permisos:**
  - **Empleado:** registra ventas y ve las del día de su local. No anula ni devuelve: se lo pide al dueño.
  - **Dueño de local:** todo, con costo y margen.
  - **Dueño de fábrica:** registra, consulta, anula y devuelve las ventas de la fábrica a sus mayoristas. No ve las ventas de los locales.
- **Ventas de la fábrica a mayoristas:**
  - Siempre con un cliente mayorista activo de la fábrica, nunca con el cliente de un local: a los locales se les vende con entregas.
  - El precio sale de la lista mayorista del cliente, una lista de la fábrica distinta de la de locales. Un mayorista sin lista no puede comprar.
  - El stock sale del producto terminado de la fábrica, validado contra el disponible (punto 4). No se venden insumos.
  - Se emite un `comprobante_venta` X con un punto de venta de la fábrica.
  - Sin caja: la venta lleva medio de pago y fecha. A cuenta corriente, desde el módulo 7.
  - Los mayoristas no hacen pedidos por la web.
- **Reglas:**
  - **Precio:** sale de la lista del cliente, si tiene, o de la predeterminada del local. Si la entidad discrimina IVA y la lista es sin IVA, se suma el IVA. Se guarda el precio final por renglón.
  - **Descuento:** el empleado aplica hasta `descuento_maximo_empleado`; el dueño, sin tope.
  - **Costo:** se copia `costo_unitario` del costo del local al momento de la venta.
  - **Stock:** cada renglón genera un movimiento `venta` con `venta_id`, validado contra el disponible (punto 4). Si no se indica ubicación, se usa "Principal".
  - **Comprobante:** se emite un `comprobante_venta` X y se enlaza en `ventas.comprobante_id`.
  - **Medio de pago:**
    - Efectivo, transferencia o billetera virtual: en un local exige caja abierta.
    - Cuenta corriente: exige un cliente habilitado y que no pase su límite. Genera el movimiento de cuenta corriente desde el módulo 7. Hasta entonces, el medio cuenta corriente se rechaza.
  - **Anulación:**
    - Solo una venta confirmada, una vez.
    - Devuelve el stock con un movimiento `anulacion` con `venta_id`.
    - Anula el comprobante.
    - Si se cobró en un local, registra la devolución del dinero en la caja abierta del momento. Si era a cuenta corriente, registra un movimiento de cuenta corriente negativo.
  - **Devolución parcial:** igual que la anulación, pero por los renglones devueltos. El movimiento de stock es de tipo `devolucion` con `venta_id`.
  - **Meta de rendimiento:** registrar una venta en menos de 2 segundos. Un test mide el caso típico de 10 renglones.

### Tarea 6: conectar `privado/sucursal.html` a ventas y caja

- **Qué se conecta:** abrir, ver y cerrar la caja, registrar ventas y, para el dueño, ver los cierres y anular ventas, contra la API de los módulos 4 y 5.
- **Qué no:** el resto de la pantalla sigue con los datos de prueba de `shared/db.js` hasta que se conecten sus módulos.
- **Se prueba en el navegador**, con un empleado y un dueño de local de la base de desarrollo.
- No toca `index.html`, `img/` ni `.DS_Store`.

### Módulo 7: cuentas corrientes de clientes y cobranzas (paquete `cuentas`)

- **Clases:**
  - Entidades y repositorios: `MovimientoCuentaCorriente`, `TipoMovimientoCc`, `Cobranza` y `DetalleCobranza`.
  - `RegistroCuentaCorriente`: punto único, solo de inserción.
  - `CuentaCorrienteService`, `CobranzaService` y `CuentaCorrienteController`.
- **Endpoints:**
  - Cuenta del cliente: `GET /api/clientes/{id}/cuenta`, con el saldo, el saldo vencido y los movimientos paginados.
  - Cobranzas: `POST /api/cobranzas` (cliente, monto, medio y observaciones) y `GET /api/cobranzas`.
  - Anular una cobranza: `POST /api/cobranzas/{id}/anular`, con el motivo.
  - Ajuste manual: `POST /api/clientes/{id}/cuenta/ajustes`, con importe con signo y motivo.
- **Permisos:**
  - **Dueño de local:** todo.
  - **Empleado:**
    - Registra cobranzas y ve el saldo, como pide el complemento.
    - No anula cobranzas ni hace ajustes.
  - **Dueño de fábrica:** todo sobre la cuenta de sus mayoristas. La de los locales se opera en el módulo 8.
- **Reglas:**
  - **Saldo:** es la suma de los importes y nunca se guarda. Positivo es deuda del cliente.
  - **Imputación:** una cobranza genera un movimiento negativo y se imputa automáticamente a las deudas más viejas (`detalle_cobranza`). El saldo vencido son las deudas con `vence` anterior a hoy que siguen sin imputar.
  - **Recibo:** cada cobranza emite un recibo X.
  - **Caja:** si la cobra un local en efectivo, entra en el cierre de la caja abierta. Las cobranzas de la fábrica no tienen caja.
  - **Correcciones:** una cobranza anulada genera el movimiento contrario. Nada se edita.

### Módulo 8: cuenta corriente entre la fábrica y los locales (paquetes `cuentas` y `entregas`)

- **Clases:**
  - Las del módulo 7, más `CuentaFabricaService`, `CuentaFabricaController` y `CuentaLocalController`.
  - Cambios en `EntregaService`: remito al enviar, deuda al recibir y costo con el IVA que defina el contador.
- **Endpoints de la fábrica:**
  - Saldos: `GET /api/fabrica/cuentas`, con el saldo, el saldo vencido y la última entrega de cada local.
  - Movimientos de un local: `GET /api/fabrica/cuentas/{localId}`.
  - Cobranzas: `POST /api/fabrica/cobranzas`, con local, monto, medio y fecha.
  - Pagos informados por los locales: `GET /api/fabrica/pagos-informados` y `POST /api/fabrica/pagos-informados/{id}/confirmar|rechazar`.
  - Ajuste: `POST /api/fabrica/cuentas/{localId}/ajustes`, con motivo; es una nota de crédito o débito X.
- **Endpoints del local:**
  - Saldo y movimientos: `GET /api/cuenta-fabrica`.
  - Informar un pago: `POST /api/cuenta-fabrica/pagos`, con monto, medio, fecha y referencia de la transferencia. Si se paga en efectivo desde la caja, se registra además un egreso `pago_fabrica`.
- **Permisos:**
  - **Dueño de fábrica:** ve y opera la cuenta de todos los locales. No ve sus cajas ni sus ventas.
  - **Dueño de local:** ve solo su saldo y sus movimientos con la fábrica, e informa pagos.
  - **Empleado:** nada, porque el complemento le oculta la cuenta con la fábrica. Sigue registrando la recepción de mercadería como en la etapa 2.
  - **Mayoristas:** sus cuentas no aparecen acá. Son clientes comunes de la fábrica (módulo 7).
  - **Local central:** los dueños de la fábrica ven la cuenta desde los dos lados, cambiando de ámbito.
- **Reglas:** ver los puntos 5 y 9.

### Módulo 9: pedidos online (paquete `pedidos`)

- **Clases:**
  - Entidades y repositorios: `Pedido`, `DetallePedido`, `PedidoHistorial`, `EstadoPedido`, `TransicionesPedido` (la tabla de transiciones válidas, en código y en un trigger) y `ReservaStock`.
  - `PedidoService` y `PedidoPublicoService`.
  - `VencimientoPedidos`: una tarea programada, `@Scheduled`, cada minuto.
  - Controladores: `PedidoController` y `PedidoPublicoController`.
- **Endpoints públicos, sin login:**
  - Pedir: `POST /api/publico/pedidos`, con local, renglones, datos de contacto, modalidad y medio de pago elegido. Devuelve el código de seguimiento.
  - Seguir: `GET /api/publico/pedidos/{codigo}`. Muestra el estado y, si está confirmado, cómo pagar (punto 7). No muestra datos internos.
  - Cancelar: `POST /api/publico/pedidos/{codigo}/cancelar`, mientras no esté pagado.
- **Endpoints internos:**
  - Consultar: `GET /api/pedidos` (filtros: estado y fechas) y `GET /api/pedidos/{id}`, con el historial.
  - Confirmar: `POST /api/pedidos/{id}/confirmar`, que reserva el stock.
  - Rechazar: `POST /api/pedidos/{id}/rechazar`, con motivo.
  - Avanzar el estado: `POST /api/pedidos/{id}/estado`.
  - Reasignar: `POST /api/pedidos/{id}/reasignar`, con el local destino y un motivo.
  - Cobrar: `POST /api/pedidos/{id}/cobro`, que crea la venta con `pedido_id` y consume la reserva.
  - Cancelar: `POST /api/pedidos/{id}/cancelar`, con motivo.
- **Permisos:**
  - **Dueño de local y empleado:** gestionan los pedidos de su local. El complemento le da al empleado confirmar, rechazar y actualizar el estado.
  - **Dueño de fábrica:** nada.
- **Estados y transiciones:**
  - `pendiente` pasa a `confirmado`, `rechazado` o `cancelado`.
  - `confirmado` pasa a `en_preparacion`, `cancelado` o `vencido` (este último solo sin pago).
  - `en_preparacion` pasa a `listo_retiro` (retiro en el local), `pendiente_envio` (envío coordinado), `cancelado` o `vencido` (solo sin pago).
  - `listo_retiro` y `pendiente_envio` pasan a `finalizado` (solo con la venta registrada), `cancelado` o `vencido` (solo sin pago).
  - `rechazado`, `cancelado`, `vencido` y `finalizado` son finales.
  - Cada cambio guarda una fila en `pedido_historial`, con el usuario o null si lo hizo el sistema.
- **Reglas:**
  - **Pedido pendiente:** no reserva. Al crearlo se valida que el local esté visible en la web y que los artículos estén publicados, con precio en su lista.
  - **Precio:** se toma al crear el pedido y no cambia.
  - **Confirmar:** reserva el stock (punto 4), habilita el pago y fija `vence_at` = ahora + `horas_vencimiento_pedido` del local.
  - **Rechazar, cancelar o vencer:** liberan la reserva. Cancelar un pedido cobrado exige anular antes su venta.
  - **Reasignar:** solo un pedido `pendiente`, sin reserva todavía. Queda en el historial con el local anterior y el nuevo.
  - **Ubicación de la reserva:** "Principal" del local, salvo que quien confirma indique otra de su local.
  - **Pedidos falsos:** límite de pedidos por IP y por hora, un campo trampa (honeypot) para bots y un máximo de renglones y de cantidad por renglón.
  - **IP real, requisito del módulo:** detrás de un servidor intermedio (proxy inverso o balanceador), la IP del pedido es la del servidor intermedio, y el límite por IP no sirve.
    - Antes de publicar hay que configurar ese servidor para que mande la IP del cliente en `X-Forwarded-For`, y la app para que confíe en esa cabecera solo si viene de él (`server.forward-headers-strategy` y la lista de proxies confiables).
    - Sin esa configuración, cualquiera podría falsificar la cabecera y saltarse el límite.
  - **Código de seguimiento:** aleatorio, de al menos 22 caracteres (`SecureRandom`, unos 128 bits en base64 URL), único. Las consultas y cancelaciones por código también tienen un límite por IP, para que no se puedan probar códigos.
  - **Aviso al cliente:** el local ve un enlace de WhatsApp con el mensaje armado para cada cambio de estado. El envío automático de correos queda para la etapa 6 (punto 8.11).

## 4. El disponible con las reservas, y cómo se evita vender lo que no hay

- **Disponible = cantidad actual − reservas activas**, por artículo y ubicación.
  - El disponible de un local es la suma de sus ubicaciones.
  - El catálogo público pasa a mostrar "disponible" con este cálculo, en lugar de cantidad actual > 0.
- **`stock_articulos.cantidad_reservada`** es un caché que mantiene la base:
  - Un trigger en `reservas_stock` suma cuando se crea una reserva activa y resta cuando pasa a consumida o liberada.
  - Así el disponible se lee de una sola fila, sin sumar reservas en cada consulta.
- **Reservas vencidas:**
  - Antes de reservar o vender un artículo, con su fila de stock ya bloqueada, se liberan las reservas de pedidos vencidos de ese artículo y se pasan esos pedidos a `vencido`.
  - Así un pedido vencido nunca le quita disponible a otro, aunque la tarea programada no haya corrido.
  - La tarea programada (`VencimientoPedidos`, cada minuto) libera el resto.
- **Ubicación:** las reservas se hacen en la ubicación "Principal" del local, salvo que se indique otra.
- **Reservar:**
  - Al confirmar el pedido se bloquea la fila de stock (`SELECT ... FOR UPDATE`, en el mismo orden de bloqueo que `RegistroStock`) y se valida que el disponible alcance para cada renglón.
  - Si un renglón no alcanza, no se reserva ninguno y la API responde 409 con el artículo.
- **Vender:** la venta valida contra el disponible con la fila bloqueada.
  - Una venta de mostrador no puede llevarse lo reservado por un pedido.
  - La venta que cobra un pedido consume primero su propia reserva y descuenta la cantidad, todo en la misma transacción.
- **Garantía en la base:**
  - Para los movimientos que venden o sacan mercadería (`venta` y `entrega_salida`), el trigger de stock exige que después del movimiento la cantidad actual siga siendo mayor o igual que la reservada.
  - Para `ajuste` y `conteo` no lo exige, porque un conteo tiene que poder registrar la realidad aunque falte mercadería reservada.
- **Reservas sin stock:**
  - Si un conteo o un ajuste deja la cantidad por debajo de lo reservado, el pedido afectado se marca con una alerta para el local: "reserva sin stock".
  - El local decide si lo cancela, lo reasigna o espera una entrega.
- **Concurrencia:** dos confirmaciones o ventas simultáneas del último artículo se ordenan con el bloqueo de fila. La segunda ve el disponible ya descontado y falla, igual que en la etapa 2.
- **Tests:**
  - Venta y confirmación simultáneas del último artículo.
  - Vencimiento que libera la reserva y deja vender.
  - Consistencia de `cantidad_reservada` contra la suma de reservas activas.

## 5. Cuenta corriente entre la fábrica y los locales

- **Una sola cuenta por local**, sin duplicar movimientos:
  - Los movimientos están en `movimientos_cuenta_corriente`, con `ambito_id` = la fábrica (la acreedora) y `cliente_id` = el cliente que representa al local.
  - El local ve esa misma cuenta, filtrada por su `ambito_representado_id`.
- **El saldo sale de los movimientos:** suma de importes, nunca editable. Los movimientos son solo de inserción.
- **Qué genera cada movimiento:**
  - **Entrega recibida:** un movimiento positivo de tipo `entrega` por lo recibido (decisión 10 de la etapa 2), con `vence` = fecha de recepción + `plazo_pago_dias` del local. El importe incluye el IVA según lo que defina el contador (punto 9.3).
  - **Cobranza de la fábrica** o **pago informado y confirmado:** un movimiento negativo de tipo `cobranza`, con un recibo X de la fábrica.
  - **Nota de crédito o débito por ajuste:** el movimiento del signo que corresponda, con su comprobante X y un motivo.
- **Pagos del local:**
  - El complemento pide que la fábrica registre cobranzas y que el local registre sus pagos. Si los dos registraran lo mismo, el pago se contaría dos veces.
  - Propuesta: el local informa el pago (`cobranzas.estado = informada`), que no mueve el saldo. Cuando la fábrica lo confirma, pasa a `confirmada` y recién ahí genera el movimiento. Si lo rechaza, no cambia nada y queda el motivo.
  - Mientras tanto, el local ve el saldo y aparte "pagos informados pendientes de confirmar".
- **Vencidos:** la fábrica ve, por local, el saldo vencido (las deudas vencidas sin imputar), con una señal en el listado.
- **Remito:** al enviar una entrega se emite un remito X de la fábrica, con precios, y se enlaza en `entregas.remito_id`. La factura de la fábrica al local llega con ARCA en la etapa 6.
- **Diferencias:**
  - Como la deuda es por lo recibido, una diferencia ya no suma deuda por el faltante. La resolución (`reingreso` o `perdida`) sigue siendo solo de stock.
  - La nota de crédito de la entrega queda para bonificar o devolver mercadería ya recibida, por ejemplo un pallet en mal estado. Ver el punto 8.9.
- **Entregas de la etapa 2 con total guardado:**
  - Según `CLAUDE.md`, las entregas no se usaron en producción: solo hay datos de prueba y de desarrollo. Igual la migración las cubre, para no perder deuda en ninguna base.
  - **Qué hace la migración:** para cada entrega `recibida` o `diferencia_informada` sin movimiento de cuenta corriente, inserta uno de tipo `entrega`.
  - **Importe:** sale de `detalle_entregas` (suma de cantidad recibida × precio unitario, redondeado por renglón), **no** de `entregas.total`, que es el total de lo enviado.
  - **Fechas:** la del movimiento es la de recepción, y el vencimiento se calcula con el plazo del local.
  - **IVA:** con el mismo criterio que se apruebe en el punto 9.3, aplicado igual a las entregas viejas y a las nuevas.
  - **Remitos:** las entregas viejas quedan sin remito, para no numerar comprobantes con fechas pasadas. Las anuladas y las enviadas sin recibir no generan movimiento.
  - **Test:** una base con entregas de la etapa 2, después de migrar, tiene el saldo esperado por local.
- **Antes de usar las entregas en producción:** tienen que estar este módulo y la definición del contador sobre el IVA (punto 9.3).

## 6. Comprobantes de esta etapa y los que quedan para ARCA

- **En esta etapa, todos no fiscales, letra X y con la leyenda "Documento no válido como factura":**
  - Comprobante de venta (`comprobante_venta`), en cada venta de mostrador, en la que cobra un pedido y en cada venta de la fábrica a un mayorista.
  - Remito X de la fábrica en cada entrega, con precios.
  - Recibo X en cada cobranza: del local a su cliente y de la fábrica al local.
  - Nota de crédito X en las anulaciones y devoluciones de ventas con comprobante, y en los ajustes a favor de un cliente o de un local. Nota de débito como ajuste en contra (ver punto 8.9).
  - Presupuesto X: lo pide el complemento. Propuesta: dejarlo para el final de la etapa o para la etapa 5, porque no mueve stock ni dinero.
- **Numeración:**
  - Correlativa por punto de venta, tipo y letra, sin huecos.
  - `NumeradorComprobantes` bloquea la fila de `punto_venta_comprobantes` y usa `ultimo_numero`. El índice único de `comprobantes` es la garantía final.
  - Cada ámbito necesita un punto de venta activo con los tipos X que usa. Los configura el administrador técnico.
- **Cálculo del IVA:**
  - Si la entidad emisora discrimina IVA, el comprobante muestra el neto y el IVA por alícuota.
  - Si no, muestra solo el total.
  - En los X es informativo; deja los datos listos para la factura.
- **Para la etapa 6, con ARCA,** según la condición de cada entidad (reglas en `ReglasComprobante`, a confirmar con el contador):
  - **Emisor responsable inscripto:**
    - Factura y nota de crédito A a receptores responsables inscriptos.
    - B a consumidores finales y exentos.
    - Para monotributistas, la letra la confirma el contador, porque la normativa cambió en los últimos años.
  - **Emisor monotributista o exento:** factura y nota de crédito C a cualquier receptor.
  - **Consumidor final:** no puede emitir comprobantes fiscales. Ninguna de las cuatro entidades debería quedar así si factura (punto 8.12).
  - **De la fábrica a cada local:** factura en cada entrega, según la condición de los dos (`entregas.factura_id`), más la nota de crédito fiscal en devoluciones.
  - **De la fábrica a los mayoristas:** factura en cada venta de la Línea Jardinería, según la condición frente al IVA de cada mayorista (A a un responsable inscripto; para los demás, la letra que confirme el contador), más la nota de crédito fiscal en anulaciones y devoluciones. A confirmar con el contador.
  - **Remito:** el remito oficial es letra R, que hoy no está en `letra_comprobante`. Si el contador lo pide, se agrega con ARCA.
  - **Datos del cliente:** se completan desde su CUIT consultando ARCA.
- **Datos de desarrollo:** hoy hay dos entidades responsables inscriptas (Fábrica y Ruta 1), una monotributista (La Rioja) y una exenta (Mitre). Son inventados: hace falta la condición real de cada una (punto 8.12).

## 7. El pago de los pedidos web

- **Qué ya está decidido:**
  - El pago se habilita solo cuando el local confirma el pedido (regla 5 de `CLAUDE.md`).
  - Los pagos online automatizados están fuera de alcance según el complemento. No hay integración con Mercado Pago.
- **Propuesta para esta etapa:** "habilitar el pago" significa mostrarle al cliente, en el seguimiento de su pedido confirmado, cómo pagar según el medio que eligió.
  - **Transferencia:**
    - Se muestran el alias y el CBU del local (`ambitos.datos_transferencia`), el total y la fecha límite (`vence_at`).
    - El local verifica la acreditación en su banco y registra el cobro, que crea la venta y consume la reserva.
  - **Efectivo al retirar:** el pedido se cobra en el mostrador. El vencimiento corre igual, como plazo para retirar.
- **Al cobrar:** se crea la venta con `pedido_id`, el medio de pago y la caja abierta. Descuenta el stock y emite el comprobante X.
- **Vencimiento:**
  - Un pedido confirmado sin cobro al llegar a `vence_at` pasa a `vencido` y libera la reserva.
  - Lo hace la tarea programada, o antes, al reservar o vender ese artículo (punto 4).
- **Decidido:**
  - Medios en la web: transferencia y efectivo al retirar. Sin link de Mercado Pago.
  - Plazo: 48 horas para pagar o retirar, configurable por local.
  - El cliente no sube el comprobante de la transferencia: el local verifica la acreditación en su banco.
- **Datos de transferencia:** los carga cada dueño de local en el sistema. Nunca se escriben alias, CBU ni otros datos reales en archivos del repositorio, ni en tests ni en documentos.

## 8. Qué falta definir, con una propuesta para cada punto

1. **Cuentas web de los clientes.**
   - El complemento pide que el cliente registrado vea el estado y el historial de sus pedidos.
   - Propuesta: en esta etapa, pedidos sin cuenta, con un código de seguimiento (un link). Las cuentas web, con login propio, separado del de los usuarios internos, quedan para una etapa posterior, en una tabla `cuentas_web` global, enlazada con el cliente de cada local.
2. **Clientes por local.**
   - Propuesta: cada local tiene su lista de clientes (`clientes.ambito_id`). Una persona que compra en dos locales es un cliente en cada uno.
   - El pedido web crea o reutiliza el cliente del local asignado, buscando por email o teléfono, solo si el local lo acepta al confirmar.
3. **Comprobante de una venta sin factura.**
   - Propuesta: un tipo nuevo, `comprobante_venta` letra X, numerado por punto de venta, en `ventas.comprobante_id`.
   - En la etapa 6, al vender se elige X o factura.
4. **PDF.**
   - Propuesta: una vista imprimible en HTML, que el navegador guarda como PDF. Así no hace falta una librería de PDF en el servidor.
   - Si hace falta un PDF generado por el servidor (por ejemplo, para mandarlo por mail), se agrega en la etapa 6.
5. **Pagos mixtos.**
   - Propuesta: un solo medio de pago por venta, como dice el modelo.
   - Pagar parte en efectivo y parte por transferencia queda para después: necesita una tabla `pagos_venta`.
6. **Descuentos.**
   - Propuesta: el dueño de local aplica cualquier descuento. El empleado, hasta el porcentaje que fije su dueño (`descuento_maximo_empleado`, 0 por defecto).
   - Las promociones con fecha siguen en la etapa 6.
7. **Caja de la fábrica.**
   - Decidido: la fábrica no tiene caja en esta etapa. Sus cobros llevan medio de pago y fecha, sin sesión de caja.
8. **Una caja por local, no por persona.**
   - Propuesta: una sola sesión abierta por local, que funciona como turno.
   - Si dos empleados atienden a la vez, comparten la caja. Cada venta y movimiento guarda quién lo hizo.
9. **Notas de crédito de las entregas.**
   - Propuesta: como la deuda es por lo recibido, la nota de crédito X de la fábrica sirve para devoluciones o bonificaciones después de recibir.
   - La devolución física de mercadería del local a la fábrica (que el stock vuelva) queda para la etapa 4, con las compras.
10. **Límite de crédito de los clientes.**
    - Propuesta: opcional por cliente. Sin límite, la cuenta corriente no tiene tope.
    - Los locales con la fábrica no tienen límite, solo plazo.
11. **Avisos al cliente.**
    - Propuesta: un enlace de WhatsApp con el mensaje armado, que el local abre con un clic. No necesita servidor de correo.
    - El correo automático queda para la etapa 6, junto con las notificaciones.
12. **Condición fiscal real de cada entidad.**
    - Hacen falta el CUIT y la condición real de la fábrica y de cada local, y que el contador confirme las letras.
    - Una entidad "consumidor final" no puede emitir comprobantes fiscales.
    - No bloquea esta etapa, porque los comprobantes son X, pero sí la 6.
13. **Backups y hosting.**
    - El complemento pide backups automáticos desde que se carguen datos reales, y la etapa 3 es la primera con datos reales.
    - Propuesta: `pg_dump` diario, 30 días de retención y copia fuera del servidor, con una restauración probada antes de cargar datos.
    - Falta saber dónde va a estar el sistema: servidor propio, VPS o un servicio administrado, que puede incluir backups.
14. **Front.**
    - Propuesta: como en las etapas anteriores, solo API y tests.
    - Hace falta conectar el front para que los clientes hagan pedidos y los locales vendan, en dos tareas aparte:
      - `privado/sucursal.html` a la API de ventas, caja y pedidos.
      - `index.html` al catálogo y a los pedidos. Tiene cambios locales sin commitear.
15. **Presupuestos.**
    - Propuesta: módulo opcional al final de la etapa, porque no mueve stock ni dinero.

## 9. Notas de "Para revisar en la etapa 3"

### 9.1 Un dueño de local que ajusta stock de productos de la fábrica sin entrega ni deuda

- **El riesgo:** un ajuste positivo de un producto terminado de la fábrica en un local hace aparecer mercadería sin entrega ni deuda.
- **Propuesta, en el módulo 8:**
  - **Bloquear los ajustes positivos** (`ajuste`) de productos de la fábrica en un local.
    - El servicio lo rechaza y la base lo garantiza con el trigger de `movimientos_stock`.
    - Los ajustes negativos (rotura, merma, robo) siguen permitidos, con motivo.
  - **Mercadería sobrante:** el camino es una entrega. Si la fábrica mandó de más y no quedó en la entrega, la fábrica lo regulariza con una entrega nueva.
  - **Conteos positivos:** siguen permitidos, porque el conteo registra la realidad. Cada uno genera una alerta para la fábrica.
    - La alerta solo dice artículo, local, cantidad y fecha.
    - La fábrica no ve el stock del local.
- **Decidido, pendiente de acuerdo con los dueños de Mitre y Ruta 1:** la alerta la ve la fábrica, solo con artículo, local, cantidad y fecha. Es un dato del local, aunque sea sobre un producto de la fábrica.

### 9.2 Una entrega enviada que nunca se recibe

- **El riesgo:** la mercadería salió de la fábrica y no entró al local, así que no aparece en ningún stock. Tampoco hay deuda, porque la deuda nace al recibir.
- **Propuesta, en el módulo 8:**
  - **"En tránsito":** el stock de la fábrica muestra aparte las cantidades enviadas sin recibir, para que no desaparezcan.
  - **Aviso por demora:** una entrega `enviada` hace más de N días (propuesta: 3 días, configurable) aparece como demorada para la fábrica y para el local.
  - **Sin vencimiento automático:** anular o recibir sigue siendo una decisión de las personas, porque anular devuelve stock que puede ya no estar en la fábrica.

### 9.3 El IVA en el costo y en la deuda de las entregas

- **Hoy:**
  - Al recibir, el costo en el local es el precio unitario de la entrega, tal cual.
  - La deuda todavía no existe; la crea el módulo 8.
- **El problema:** no es solo el costo, también el importe de la deuda.
  - Si la fábrica es responsable inscripta y su lista para locales es sin IVA, el local le debe el precio más el IVA.
  - Si el local es responsable inscripto, ese IVA es crédito fiscal y su costo es el neto.
  - Si el local es monotributista o exento, el IVA no se recupera y su costo es el precio con IVA.
- **Propuesta, a confirmar con el contador:**
  - **Deuda:** el precio de la lista de la fábrica, más el IVA si la fábrica discrimina IVA y la lista es sin IVA.
  - **Costo en el local:** el neto si el local discrimina IVA, y el precio con IVA si no.
- **Implementación:**
  - Se cambia `EntregaService.actualizarCostos`.
  - Se usa la misma regla en el movimiento de cuenta corriente y en la migración de las entregas viejas.
  - Tests con las cuatro combinaciones de condición de la fábrica y del local.
- **Bloquea:** el uso de las entregas en producción, no el resto de la etapa.
