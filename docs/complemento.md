# Green Fire – Complemento de requisitos del sistema de gestión

Este documento complementa `requerimientos` (el documento original). Si hay contradicción entre ambos, manda este complemento. Está redactado con el mismo formato ("El sistema deberá…").

## Decisiones tomadas y pendientes

| # | Decisión | Por qué importa | Resolución |
| --- | --- | --- | --- |
| 1 | Roles y ámbito de cada usuario | Hay tres perfiles: dueño de fábrica, dueño de local y empleado de local. Los dos dueños de la fábrica son también dueños del local central; los demás locales tienen sus propios dueños y empleados | Resuelto: tres perfiles con interfaz y datos distintos (ver "Roles y permisos"). Un usuario puede tener un perfil en cada ámbito, así los dueños de la fábrica entran también a su local con una sola cuenta |
| 2 | Condición fiscal de cada punto de venta | El tipo de comprobante y el IVA dependen de la condición fiscal de cada entidad | Resuelto: la fábrica y cada local tienen una condición fiscal distinta. Configurar por entidad fiscal el CUIT, la condición frente al IVA, los puntos de venta y los tipos de comprobante permitidos |
| 3 | CUIT por local | La numeración, la caja y los reportes de facturación se separan por entidad fiscal, no solo por sucursal | Resuelto: la fábrica y cada local son una entidad fiscal propia, asociada a sus puntos de venta |
| 4 | Cómo llega el sustrato de la fábrica a los locales | La fábrica y los locales tienen dueños distintos, la entrega es una venta de la fábrica al local, con precio, comprobante y deuda, y no una transferencia interna de stock | Confirmado: la fábrica cobra lo que entrega. Tratar a la fábrica como proveedor de cada local y a cada local como cliente de la fábrica: remito, cuenta corriente y cobranza entre ambos, con recepción confirmada por el local |
| 5 | Cuándo se descuenta el stock de un pedido online | El cliente puede no pagar ni retirar | Resuelto: reservar al confirmar, descontar al entregar o cobrar, y liberar la reserva si el pedido vence sin pago |

**Decisión pendiente (menor):** qué significa "habilitar el pago" en la web. Puede ser un link de Mercado Pago, los datos para transferir o el cobro en el local al retirar. Cada una requiere campos distintos en el pedido. Debe decidirse antes de implementar los pedidos online.

## Roles y permisos

Hay tres perfiles de acceso. Cada uno tiene su propia interfaz y solo ve los datos de su ámbito. Los dos dueños de la fábrica entran con una sola cuenta y ven la fábrica y el local central, porque son dueños de ambos. La tabla es una propuesta a validar.

| Función | Dueño de fábrica | Dueño de local | Empleado de local |
| --- | --- | --- | --- |
| Ámbito de datos | La fábrica, y el local central si es dueño | Su local | Solo el local donde trabaja |
| Ventas presenciales y pedidos online | Ventas al por mayor de la Línea Jardinería a paisajistas, viveros y growshops; no hace ventas minoristas ni pedidos online | Ver y gestionar | Registrar ventas, confirmar o rechazar pedidos y actualizar su estado |
| Anular ventas y devoluciones | Sí, de sus ventas mayoristas | Sí | No: lo solicita al dueño |
| Caja | No ve la de los locales | Abre, cierra y consulta todo el historial | Abre y cierra su turno; ve solo la caja del día |
| Costos y márgenes | Sí | Sí | No |
| Precios y catálogo | Listas de precios para locales y mayorista, y productos de fábrica | Precios y catálogo de su local | No |
| Stock | Fábrica: insumos y producto terminado | Consulta y ajustes con motivo | Solo consulta, y registrar la recepción de mercadería |
| Cuenta corriente con la fábrica | Ve el saldo de cada local y registra cobranzas | Ve su saldo y registra pagos | No |
| Cuentas corrientes de clientes | De sus mayoristas | Sí | Registrar cobranzas y ver el saldo |
| Compras y proveedores | Insumos de producción | Compras de su local | No |
| Producción | Sí | No | No |
| Reportes | De la fábrica | De su local | Solo el resumen del día |
| Usuarios | No | Crea y desactiva a los empleados de su local | No |

- El sistema deberá mostrar a cada perfil una interfaz distinta: el empleado ve solo las pantallas operativas de su local (ventas, pedidos, stock, caja del día y cobranzas).
- El sistema deberá ocultar al empleado los costos, los márgenes, la cuenta corriente con la fábrica y los reportes financieros.
- El sistema deberá validar los permisos en el servidor y no solo ocultar botones, para que un empleado no pueda acceder a un dato cambiando la dirección o la consulta.
- El sistema deberá permitir que una persona con perfiles en dos ámbitos elija con cuál entra, cambie sin cerrar sesión e indique siempre en pantalla el ámbito activo.
- El sistema deberá permitir que cada dueño de local cree, edite y desactive a los empleados de su local, sin ver los de otros locales.
- El sistema deberá incluir un administrador técnico que cree las cuentas de los dueños y no vea los datos comerciales de ningún local. Ese rol lo cumple la desarrolladora del sistema, que se encarga también de su mantenimiento.
- El sistema deberá registrar en la auditoría quién entró, con qué perfil y en qué ámbito.

## Requerimientos nuevos

### Entregas y control de stock

- El sistema deberá registrar cada entrega de la fábrica a un local como un remito con precio, que genera una deuda del local con la fábrica, con estados enviada, recibida y diferencia informada.
- El sistema deberá descontar el stock en el origen al enviar y sumarlo en el destino recién cuando el destino confirme la recepción.
- El sistema deberá definir stock mínimo y máximo por producto y ubicación, y alertar los productos por debajo del mínimo.
- El sistema deberá registrar cada variación de stock como un movimiento con tipo (venta, compra, producción, entrega, ajuste), cantidad, usuario y fecha, de modo que el stock actual sea la suma de sus movimientos.
- El sistema deberá exigir un motivo en todo ajuste manual de stock.
- El sistema deberá permitir un conteo físico periódico con registro de las diferencias contra el stock del sistema.
- El sistema deberá reservar el stock de un pedido online confirmado y liberarlo si el pedido se rechaza o vence sin pago.

### Venta de la fábrica a los locales

- El sistema deberá permitir a la fábrica vender a cada local con una lista de precios propia para locales, distinta de la de venta al público.
- El sistema deberá emitir en cada entrega un comprobante de la fábrica al local (remito y, según su condición fiscal, factura), con la numeración y el punto de venta de la fábrica.
- El sistema deberá llevar una cuenta corriente entre la fábrica y cada local, con saldo calculado a partir de entregas, cobranzas y devoluciones.
- El sistema deberá permitir a la fábrica registrar las cobranzas de cada local con fecha, monto y medio de pago, y al local registrar sus pagos a la fábrica.
- El sistema deberá registrar la entrega como una compra del local a la fábrica, que ingresa a su stock cuando el local confirma la recepción y queda valuada al precio de esa entrega.
- El sistema deberá permitir que el local informe diferencias de recepción y que la fábrica las resuelva con un ajuste o nota de crédito.
- El sistema deberá definir un plazo de pago por local y señalar a la fábrica los saldos vencidos.
- El sistema deberá mostrar a cada local solo su saldo con la fábrica, y a la fábrica el saldo de cada local, sin que la fábrica vea la caja interna de los locales.

### Precios y listas

- El sistema deberá permitir varias listas de precios (por ejemplo minorista y mayorista) y asociar a cada cliente la que le corresponde.
- El sistema deberá permitir actualizar precios de forma masiva, por porcentaje o importando una planilla.
- El sistema deberá guardar el historial de cambios de precio con usuario y fecha.
- El sistema deberá registrar el costo de cada producto y mostrar el margen sobre el precio de venta.
- El sistema deberá permitir definir descuentos y promociones con fecha de inicio y fin.

### Clientes y cuentas corrientes

- El sistema deberá permitir crear, editar, buscar y desactivar clientes, tanto los registrados desde la web como los cargados en el mostrador.
- El sistema deberá guardar de cada cliente nombre o razón social, CUIT o DNI, condición frente al IVA, contacto y dirección.
- El sistema deberá permitir vender a cuenta corriente y registrar cobranzas, calculando el saldo del cliente a partir de sus movimientos y no como un valor editable.
- El sistema deberá mostrar el historial de compras y pedidos de cada cliente.
- El sistema deberá permitir que el cliente registrado vea desde la web el estado y el historial de sus pedidos.

### Comprobantes y facturación

- El sistema deberá generar para cada venta presencial un comprobante imprimible o descargable en PDF, con numeración correlativa por punto de venta.
- El sistema deberá emitir presupuestos, remitos y recibos, además del comprobante de venta.
- El sistema deberá permitir anular una venta o registrar una devolución, revirtiendo el stock y el movimiento de caja y dejando registro del motivo.
- El sistema deberá registrar si cada venta tiene o no comprobante fiscal asociado.
- El sistema deberá emitir factura electrónica con CAE a través de los servicios web de ARCA, según la condición fiscal configurada para cada punto de venta.
- El sistema deberá completar los datos fiscales del cliente a partir de su CUIT o DNI consultando ARCA.

### Condición fiscal por entidad

- El sistema deberá tratar a la fábrica y a cada local como entidades fiscales independientes, cada una con su CUIT, su condición frente al IVA, sus puntos de venta y su propio certificado digital de ARCA.
- El sistema deberá determinar los tipos de comprobante permitidos según la condición fiscal de quien emite y de quien recibe.
- El sistema deberá discriminar el IVA solo en los comprobantes de las entidades a las que corresponde, y mostrar los precios con o sin IVA según el caso.
- El sistema deberá numerar los comprobantes por entidad y punto de venta, sin que una entidad vea los comprobantes de otra.
- El sistema deberá permitir a cada dueño exportar los comprobantes de su propia entidad para su contador.

### Caja y finanzas

- El sistema deberá registrar la apertura de caja con un saldo inicial.
- El sistema deberá calcular en el cierre el efectivo esperado y compararlo con el contado, informando la diferencia.
- El sistema deberá permitir registrar ingresos y egresos manuales (gastos del local, retiros, aportes) con rubro y observación.
- El sistema deberá separar el dinero cobrado por transferencia o billetera virtual del efectivo físico en caja.
- El sistema deberá impedir modificar un cierre de caja ya confirmado, salvo por un usuario autorizado y dejando auditoría.

### Compras y cuentas a pagar

- El sistema deberá llevar la cuenta corriente de cada proveedor, con saldo a pagar calculado desde las compras y los pagos.
- El sistema deberá registrar pagos a proveedores con fecha, monto y medio de pago.
- El sistema deberá permitir crear órdenes de compra, y sugerir cantidades a reponer según el stock mínimo.
- El sistema deberá actualizar el costo del insumo o producto cuando se registre una compra.
- El sistema deberá registrar descuentos y condiciones comerciales de cada proveedor.

### Producción

- El sistema deberá definir para cada producto fabricado una receta con sus componentes y cantidades.
- El sistema deberá descontar los insumos del stock al cerrar un lote de producción, y sumar el producto terminado al stock de fábrica.
- El sistema deberá registrar la merma como la diferencia entre el consumo teórico de la receta y el consumo real.
- El sistema deberá asignar un número de lote a cada producción y conservarlo en el producto terminado.
- El sistema deberá calcular el costo por lote y por unidad según el costo vigente de los insumos.
- El sistema deberá alertar los insumos por debajo de su stock mínimo.

### Pedidos online (ajustes nuevos)

- El sistema deberá definir un tiempo de vencimiento para los pedidos confirmados sin pago.
- El sistema deberá avisar al cliente por correo o por un enlace de WhatsApp cuando cambie el estado de su pedido.
- El sistema deberá permitir reasignar un pedido a otra sucursal, dejando registro del cambio.

### Reportes y tablero

- El sistema deberá mostrar un tablero con ventas del día y del mes, pedidos pendientes, stock bajo mínimo, saldos a cobrar y a pagar, y estado de caja.
- El sistema deberá permitir reportes filtrados por fecha, sucursal, producto, cliente y medio de pago.
- El sistema deberá mostrar los productos más vendidos y los de menor rotación.
- El sistema deberá mostrar a cada dueño un reporte consolidado de los ámbitos (locales o fábrica) sobre los que tiene permiso.
- El sistema deberá exportar los reportes a CSV o Excel.

### Administración

- El sistema deberá permitir que una misma persona tenga distintos roles en distintos ámbitos (por ejemplo dueño de fábrica y dueño de un local) y que nunca acceda a los datos de los demás locales.
- El sistema deberá permitir recuperar la contraseña, bloquear temporalmente tras varios intentos fallidos y cerrar sesiones inactivas.
- El sistema deberá permitir desactivar usuarios, productos, clientes y proveedores sin borrarlos, para conservar el historial.
- El sistema deberá permitir configurar los datos de la empresa, las sucursales, los puntos de venta y los medios de pago.
- El sistema deberá permitir la carga inicial de productos, clientes y proveedores desde una planilla.
- El sistema deberá realizar copias de seguridad automáticas de la base de datos y contar con un procedimiento de restauración probado.

## Ajustes al documento original

Estas frases del documento original se contradicen con lo anterior, mezclan conceptos o son demasiado vagas. Se reemplazan por lo indicado.

| Sección del documento original | Qué dice hoy | Cambiar por |
| --- | --- | --- |
| Gestión de usuarios y permisos; Administración por roles | Tres tipos de usuario: empleado, responsable de sucursal y administrativo de fábrica. Habla de "dueños de sucursal" | Definir tres perfiles: dueño de fábrica (reemplaza al administrativo de fábrica), dueño de local (equivale al responsable) y empleado de local, con interfaz y datos distintos para el empleado. Una misma persona puede tener un perfil por ámbito |
| Caja por sucursal; Seguridad | La administración de fábrica no puede ver los cierres de caja de los locales | Mantener la regla para el personal y los dueños de fábrica, salvo que un dueño de fábrica sea también dueño de ese local, y solo para ese local |
| Gestión de stock por sucursal | "Registrar ingresos de mercadería" al stock de un local | Distinguir dos orígenes: compra a proveedor y entrega de la fábrica (una venta de fábrica al local) con recepción confirmada |
| Sistema de pedidos; Integridad de datos | El stock se actualiza al confirmar el pedido | Reservar al confirmar y descontar al entregar o cobrar |
| Estados del pedido | Lista siete estados sin definir qué transición es válida | Agregar los estados cancelado y vencido, y una tabla de transiciones permitidas y quién puede ejecutarlas |
| Registro de compras | Mezcla en una misma compra tierra, componentes electrónicos y carpas | Separar compras de insumos de producción (van al stock de insumos de fábrica) y compras de mercadería para reventa (van al stock comercial). Nombrar los insumos reales del sustrato, por ejemplo turba, perlita, humus de lombriz y champicompost, en lugar de "tierra" |
| Registro de ventas presenciales | Productos, cantidades, precios, fecha y usuario | Sumar cliente opcional, descuento, medio de pago por venta, comprobante asociado y anulación |
| Requerimientos no funcionales: Rendimiento y Disponibilidad | "Tiempos adecuados", "sin interrupciones" | Metas medibles, por ejemplo catálogo y fichas en menos de 3 segundos, registro de una venta en menos de 2 segundos, disponibilidad del 99 % en horario comercial. Los números son una sugerencia a validar |
| Respaldo de información | "Contemplar mecanismos de respaldo periódico" | Backup automático diario, retención de al menos 30 días, copia fuera del servidor y restauración probada |
| Portada y objetivos | Plataforma web comercial con foco en pedidos | Reflejar que también es un sistema de gestión integral (ventas, caja, stock, compras, producción, facturación) |

## Fuera de alcance y orden de implementación

**Fuera de alcance por ahora**, aunque Fidel lo tenga:

- App de preventistas con modo offline, hojas de ruta, geolocalización de clientes y comisiones: solo tienen sentido si Green Fire tiene vendedores en la calle.
- Integración con Mercado Libre, Tiendanube y WooCommerce, porque la web pública ya comparte el stock del sistema.
- Pagos online automatizados dentro de la web (el documento original ya los deja como mejora futura).
- Gestión de cheques, asistente de IA, planes de suscripción y multiempresa.

**Orden recomendado**, de modo que cada etapa se pueda usar y probar antes de pasar a la siguiente:

1. Base del sistema: proyecto, usuarios, roles por ámbito de local o fábrica, sucursales, auditoría.
2. Catálogo, listas de precios, stock por ubicación con movimientos y entregas, y el catálogo público en la web.
3. Clientes, pedidos online con confirmación y reserva, ventas presenciales con comprobante simple, caja con arqueo y cuenta corriente entre fábrica y locales. Backups automáticos desde el momento en que se carguen datos reales.
4. Proveedores, compras, cuentas a pagar, y producción con recetas y lotes.
5. Tablero, reportes y exportaciones.
6. Facturación electrónica con ARCA, notificaciones al cliente y promociones. Depende de los trámites de certificado y punto de venta de cada entidad fiscal.
