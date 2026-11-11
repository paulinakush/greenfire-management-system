# Resumen del módulo 3 de la etapa 3: comprobantes no fiscales y numeración

## 1. Qué se hizo

- **Paquete nuevo `comprobantes`:**
  - Entidades y repositorios: `Comprobante` y `DetalleComprobante`, más `EstadoComprobante` (`emitido`, `anulado`).
  - `NumeradorComprobantes`: toma el siguiente número con `SELECT ... FOR UPDATE` sobre `punto_venta_comprobantes`.
  - `EmisorComprobantes`: el punto único para emitir y anular.
  - `CalculoComprobante`: neto, IVA por alícuota y total. Lo usan la emisión y la vista imprimible, así lo impreso es lo mismo que se guardó.
  - `ComprobanteService`, `ComprobanteController` y `VistaImprimible` (la vista en HTML).
- **Cambios en `fiscal`:**
  - `TipoComprobante` suma `comprobante_venta` y `nota_debito`, y un nombre para mostrar de cada tipo.
  - `ReglasComprobante` define las letras por tipo y por condición del emisor. La nota de crédito y la de débito ya no son solo fiscales: admiten X para cualquier emisor.
  - `ComprobantePermitido` marca sus columnas como no nulas (ver el punto 6).
- **Modelo:** `docs/modelo-datos.dbml` se actualizó antes de la migración.
- **Migración nueva:** `V12__comprobantes.sql`. No se tocó ninguna anterior.
- **Carga de desarrollo:** `ops/desarrollo/puntos-venta-x.py` suma `comprobante_venta`, `nota_credito` y `nota_debito` en X.
- **`CLAUDE.md`:** suma el paquete `comprobantes` y una sección corta con las decisiones de comprobantes.

## 2. Modelo y migración V12

- **`tipo_comprobante`:** suma `comprobante_venta` y `nota_debito`.
- **`punto_venta_comprobantes`:**
  - Suma `ultimo_numero`, el último número emitido de ese tipo y letra en el punto de venta.
  - `comprobante_venta` solo en letra X, y `factura` nunca en X. Lo garantiza un CHECK.
- **`comprobantes`:** sigue el modelo, con estos agregados:
  - Datos del emisor copiados al emitir: razón social, CUIT, condición frente al IVA, domicilio fiscal, nombre y dirección del ámbito.
  - Datos del receptor copiados al emitir: suma el tipo de documento y el domicilio a los que ya estaban.
  - Datos de la anulación: quién, cuándo y el motivo.
  - `precios_con_iva`: si los precios del detalle incluyen IVA.
  - Los campos de ARCA (`arca_estado`, `cae`, `cae_vence`) están en la tabla pero no en Java hasta la etapa 6.
- **`detalle_comprobante`:** como en el modelo. El artículo es opcional (un recibo o un ajuste no lo llevan).
- **Lo que garantiza la base, aunque se escriba por fuera de la aplicación:**
  - `ultimo_numero` solo avanza de a uno, y solo si el número anterior ya tiene su comprobante.
  - Al confirmar la transacción, el último número tomado tiene su comprobante. Es una verificación diferida, como la del total de las entregas.
  - Un comprobante solo se inserta con el último número tomado de su punto de venta, tipo y letra. El índice único `(punto_venta_id, tipo, letra, numero)` es la garantía final.
  - Si un tipo se quita de un punto de venta y se vuelve a agregar, su numeración sigue desde el último comprobante emitido.
  - Un comprobante nace emitido, en un punto de venta activo.
  - Los datos del emisor son los de la entidad fiscal del punto de venta.
  - El cliente es del ámbito del punto de venta.
  - El comprobante asociado es de la misma entidad, y solo lo llevan las notas de crédito y de débito.
  - Solo un emisor responsable inscripto tiene IVA. Para los demás, el IVA es 0 y los precios son finales.
  - Los X no tienen datos de ARCA.
  - Total igual a neto más IVA. Cada renglón con su subtotal igual a cantidad por precio. Al confirmar, al menos un renglón, y la suma de los renglones es el total (precios con IVA) o el neto (precios sin IVA).
- **Inmutabilidad, con triggers como los de `entregas` y `auditorias`:**
  - Un comprobante no se borra ni se trunca.
  - Solo pasa de `emitido` a `anulado`, una vez, con usuario, fecha y motivo, sin cambiar nada más.
  - El detalle se carga solo en la transacción que emite el comprobante. Después no se agrega, no se modifica ni se borra.
  - Para saber que el comprobante se está emitiendo, el trigger deja una marca local de la transacción (`set_config(..., true)`). Primero usé `xmin`, pero falla si la emisión corre dentro de un savepoint. La marca respeta los savepoints, y lo cubre un test.

## 3. Numeración

- **Correlativa por punto de venta, tipo y letra, sin huecos.**
- **Cómo se toma:** `NumeradorComprobantes` busca el punto de venta activo del ámbito que emite ese tipo y letra. Bloquea su fila con `SELECT ... FOR UPDATE` y avanza `ultimo_numero`, en la misma transacción que emite.
- **Varias emisiones a la vez:** la segunda espera a que termine la primera y toma el número siguiente.
- **Si la operación falla y se revierte:** el avance se revierte con ella y el número no se consume. La próxima emisión toma ese mismo número.
- **Sin punto de venta:** si el ámbito no tiene uno activo que emita ese tipo y letra, falla con 409 y un mensaje para que el administrador técnico lo configure. Por ejemplo: "'Centro' no tiene un punto de venta activo que emita comprobante de venta X. Pedile al administrador técnico que lo configure."
- **Formato del número:** punto de venta de 5 dígitos y número de 8, como en ARCA: `00001-00000023`.

## 4. Emisión (`EmisorComprobantes`)

- **Punto único:** no hay alta directa por la API. Lo llaman las ventas, las entregas y las cobranzas (módulos 5, 7 y 8), que validan los permisos antes.
- **Exige una transacción en curso (MANDATORY),** igual que el numerador. Si la operación falla después de emitir, el comprobante, su detalle, su número y su auditoría se revierten.
- **Qué emite en esta etapa:** solo letra X, con la leyenda "Documento no válido como factura".
  - Tipos: comprobante de venta, nota de crédito, nota de débito, remito, recibo y presupuesto.
  - Una factura se rechaza: queda para la etapa 6, con ARCA.
- **Antes de tomar el número, valida todo:**
  - Que haya renglones.
  - En cada renglón: un artículo o una descripción, cantidad mayor que cero con hasta tres decimales, precio no negativo y alícuota entre 0 y 100.
  - Que el artículo sea visible para el ámbito.
  - Que el cliente sea del ámbito.
  - Que el comprobante asociado sea de la misma entidad y esté emitido, y que solo lo lleve una nota de crédito o de débito.
- **Datos copiados al emitir:**
  - Del emisor: la entidad fiscal del punto de venta y su ámbito.
  - Del receptor: el cliente. Sin cliente, el receptor es un consumidor final.
  - Si después cambian, el comprobante no cambia.
- **IVA,** según la condición del emisor copiada al emitir:
  - **Emisor que discrimina IVA (responsable inscripto):** neto, IVA por alícuota y total.
    - Con precios con IVA, el total es la suma y el IVA de cada alícuota se saca de lo que suman sus renglones.
    - Con precios sin IVA, el neto es la suma y el IVA se agrega.
  - **Emisor que no lo discrimina:** los precios son finales. Neto igual al total, e IVA 0.
- **Anular:** pasa el comprobante a `anulado` con motivo obligatorio, una sola vez. El número no se libera.
- **Auditoría:** `emitir` y `anular`, con el ámbito del punto de venta como afectado.

## 5. Consulta y vista imprimible

- **Endpoints:**
  - `GET /api/comprobantes`: filtros por tipo, fechas (`desde` y `hasta`, días en la zona horaria de la empresa) y cliente, con paginado. Ordena del más nuevo al más viejo.
  - `GET /api/comprobantes/{id}`: el comprobante completo, con emisor, receptor, renglones, IVA por alícuota (solo si el emisor lo discrimina), leyenda y datos de la anulación.
  - `GET /api/comprobantes/{id}/imprimible`: la vista en HTML.
  - No hay POST, PUT ni DELETE: responden 405.
- **Permisos:**
  - Cada dueño (de fábrica o de local) ve los comprobantes de la entidad fiscal de su ámbito activo.
  - El empleado ve e imprime solo los de los puntos de venta de su local.
  - El administrador técnico recibe 403. Sin sesión, 401.
  - Pedir uno de otra entidad (o, para el empleado, de otro local) responde 404.
- **Sin datos de costo:** los comprobantes no guardan costos ni márgenes, así que la respuesta es la misma para el dueño y el empleado.
- **Vista imprimible:**
  - HTML sin librería de PDF y sin JavaScript. Se imprime o se guarda como PDF desde el navegador.
  - Muestra el emisor, un recuadro con la letra, el tipo, el número, la fecha, el receptor, los renglones y los totales, con la leyenda arriba y al pie.
  - Con un emisor que discrimina IVA, muestra la columna de alícuota, el neto gravado y el IVA de cada alícuota. Si no, solo el total.
  - Un comprobante anulado lo dice arriba, con la fecha y el motivo.
  - Todo texto que viene de la base se escapa.
  - Con los colores de la identidad, se adapta al celular y no se guarda en caché (`Cache-Control: no-store`).

## 6. Base de desarrollo

- **Respaldo antes de migrar:**
  - Con `ops/backups/respaldar.sh`, contra el contenedor de `docker compose`.
  - Archivo `greenfire-20261111-165444.dump.enc`, de 133.232 bytes, verificado con 24 tablas con datos.
  - El `.env` no tiene las variables `BACKUP_*`, así que se pasaron por el entorno:
    - Carpeta: `~/greenfire-respaldos-desarrollo`, fuera del repositorio.
    - Clave: una al azar, guardada en `~/.greenfire-clave-respaldo-desarrollo` (permisos 600). Sin ella el respaldo no se puede abrir.
- **Migración:**
  - La app arrancó y Flyway aplicó la V12 en 42 ms. La base quedó en la versión 12.
  - Se contaron las filas de cada tabla antes y después: la única diferencia es la fila nueva de `flyway_schema_history`.
  - Las 12 filas de `punto_venta_comprobantes` quedaron con `ultimo_numero` en 0.
- **Carga de puntos de venta (`puntos-venta-x.py`):**
  - Primera corrida: "actualizado" en los cuatro ámbitos. Cada punto de venta 1 quedó con comprobante de venta, nota de crédito, nota de débito, remito, recibo y presupuesto, todos X.
  - Segunda corrida: "sin cambios" en los cuatro.
- **Prueba de punta a punta contra la API:** sin sesión, `GET /api/comprobantes` respondió 401. Como administrador técnico, el listado y la vista imprimible respondieron 403.
- **Lo que encontró esta prueba:**
  - Al editar los tipos del punto de venta, Hibernate borraba y volvía a crear todas las filas de `punto_venta_comprobantes`, también las que no cambiaban: las 12 filas que había se borraron y quedaron 24 nuevas, con ids del 13 al 36.
  - La numeración no se perdía, porque la V12 la retoma desde el último comprobante emitido.
  - Igual lo corregí: con las columnas de `ComprobantePermitido` como no nulas, Hibernate borra e inserta solo las filas que cambian. Un test lo comprueba, y sin la corrección falla.
  - En la base de desarrollo no hubo daño: todavía no había comprobantes.
- **Al terminar:** la app de desarrollo quedó detenida.

## 7. Tests

- **Suite completa (`./mvnw test`): 374 pasan, 0 fallan.** 46 son nuevos.
- **`EmisorComprobantesTest` (13), el emisor y el numerador directamente:**
  - Numeración correlativa por punto de venta, tipo y letra, y separada por ámbito.
  - Sin punto de venta activo que emita el tipo, el mensaje claro, sin tocar ningún número. No usa uno desactivado ni uno sin ese tipo.
  - Con varios puntos de venta, usa el de número más bajo.
  - Editar los tipos del punto de venta no toca las filas que quedan ni reinicia la numeración. Quitar un tipo y volver a agregarlo retoma desde el último emitido.
  - Copia los datos del emisor y del receptor, y no cambian si después cambian el local, su entidad o el cliente. Sin cliente, consumidor final.
  - IVA de un responsable inscripto con precios con IVA, con dos alícuotas (21 % y 10,5 %).
  - IVA de un responsable inscripto con precios sin IVA.
  - Un monotributista sin IVA y con precios finales.
  - Rechazos antes de tomar el número: factura, sin renglones, cliente de otro local, artículo de otro local, cantidad cero, precio negativo y renglón sin descripción.
  - Notas de crédito y de débito X asociadas a un comprobante de la misma entidad; rechazo de otra entidad y de un asociado en un comprobante de venta.
  - Anular exige motivo y se hace una sola vez; el número no se libera.
  - Auditoría de emitir y anular, con el ámbito afectado.
- **`ComprobanteConcurrenciaTest` (6), con transacciones reales:**
  - **Ocho comprobantes emitidos a la vez** en el mismo punto de venta: números del 1 al 8, sin huecos ni repetidos, cada uno con su renglón y su auditoría.
  - **Una operación que falla después de emitir:** se revierte todo (comprobante, número y auditoría) y la próxima emisión toma ese número.
  - **Una emisión que la base rechaza al confirmar** (un renglón de más que no suma): el número no se consume.
  - **Una emisión que falla con el número tomado mientras otra espera:** la segunda queda bloqueada hasta que la primera se revierte, y toma el número 1.
  - Un comprobante confirmado no suma, no cambia ni borra renglones, y no se modifica ni se borra.
  - El emisor y el numerador fallan sin transacción.
- **`ComprobanteMigracionTest` (14), las garantías de la base:**
  - El número solo avanza de a uno, no retrocede, y no se saltea uno sin comprobante.
  - Un número tomado sin comprobante no se confirma.
  - Un comprobante solo se inserta con el número tomado, y no repetido.
  - Volver a agregar un tipo retoma desde el último emitido.
  - Comprobante de venta solo X, y factura nunca X.
  - Datos del emisor, cliente del ámbito y punto de venta activo.
  - IVA solo para el responsable inscripto.
  - Renglones obligatorios, subtotal igual a cantidad por precio, y la carga del detalle dentro de un savepoint.
  - No se modifica ni se borra; solo se anula una vez, con motivo y sin cambiar nada más.
  - Nace emitido; solo las notas llevan asociado, y de la misma entidad.
- **`ComprobanteApiTest` (10), por la API:**
  - Cada dueño ve los de la entidad de su ámbito. Un dueño con dos locales de la misma entidad ve los de los dos.
  - El empleado ve e imprime solo los de su local, aunque otro local sea de la misma entidad.
  - Nadie ve los de otra entidad: 404 en el detalle y en la vista imprimible.
  - Administrador técnico 403; sin sesión 401.
  - Sin alta, edición ni borrado: 405.
  - Filtros por tipo, fechas y cliente, y paginado.
  - Detalle con IVA discriminado solo para el responsable inscripto, sin costos ni márgenes, y con los datos de la anulación.
  - Vista imprimible con la leyenda, la letra, el número y los importes con formato argentino. IVA por alícuota solo si el emisor lo discrimina. Textos escapados.
- **`PuntoVentaApiTest` (3 nuevos):**
  - Los tipos X nuevos se admiten para cualquier condición.
  - Comprobante de venta solo X; factura nunca X.
  - Las notas de crédito y de débito fiscales siguen la condición del emisor.

## 8. Decisiones que tomé y conviene revisar

- **Entidad compartida:** "cada dueño ve los comprobantes de la entidad de su ámbito", como pediste.
  - Si dos locales compartieran entidad fiscal, el dueño de uno vería los del otro. El empleado no.
  - Hoy las cuatro entidades son distintas, así que no pasa. Si algún día dos locales comparten entidad, conviene revisarlo con la regla 3 ("nadie accede a datos de otro local").
- **Los remitos de la fábrica a un local** los emite la entidad de la fábrica. El dueño del local no los ve en `/api/comprobantes`; los va a ver desde la entrega y su cuenta corriente, en el módulo 8.
- **Varios puntos de venta con el mismo tipo en un ámbito:** se usa el de número más bajo. Si hace falta elegirlo (por ejemplo, uno por caja), se agrega con las ventas.
- **Precios con IVA en un comprobante que discrimina IVA:** los renglones muestran el precio con IVA, y abajo van el neto gravado y el IVA de cada alícuota. No calculo un neto por renglón, porque redondear cada uno haría que la suma no diera el total cobrado.
- **Remito, recibo y presupuesto** siguen admitiendo cualquier letra en el punto de venta, como antes. No lo cambié porque no estaba pedido. El remito oficial (letra R) queda para la etapa 6.
- **La auditoría de la emisión** la registra el emisor. Las ventas, entregas y cobranzas van a registrar además su propia operación.
- **El respaldo de desarrollo** quedó en tu carpeta personal, con la clave al lado (en un archivo aparte). Para desarrollo alcanza; en el servidor la clave va fuera de la máquina, como dice `docs/backups.md`.

## 9. Pendiente

- **Módulo 4 (caja):** no se empezó.
- **Conectar la emisión:** las ventas (módulo 5), las cobranzas (módulo 7) y las entregas (módulo 8) todavía no emiten comprobantes. Lo harán con `EmisorComprobantes`.
- **Etapa 6:** facturas y notas fiscales con ARCA, el mapeo en Java de `arca_estado`, `cae` y `cae_vence`, y las letras que confirme el contador.
