# Plan de la etapa 2: catálogo, precios y stock

Basado en `CLAUDE.md`, `docs/complemento.md` y `docs/modelo-datos.dbml`. Aprobado, con las decisiones del punto 7 y las correcciones del punto 8. Se programa de a un módulo por vez, y cada módulo espera aprobación antes de pasar al siguiente.

## 1. Tablas del modelo que entran

- **Catálogo:** `categorias` y `articulos`.
- **Precios:** `listas_precios`, `lista_precios_items` y `historial_precios`.
- **Stock:** `ubicaciones_stock`, `stock_articulos`, `movimientos_stock`, `conteos_stock` y `detalle_conteo`.
- **Entregas:** `entregas` y `detalle_entregas`, sin las columnas que dependen de la etapa 3 (ver punto 5).
- **Columnas que se agregan después:** `movimientos_stock` se crea sin `venta_id`, `pedido_id` ni `compra_id`. Cada una se agrega con su etapa, igual que la columna del ámbito afectado de la auditoría en V3.
- **Columnas sin FK por ahora:** `lote_id` de `movimientos_stock` y de `detalle_entregas` se crea sin FK, porque `lotes_produccion` es de la etapa 4. La FK se agrega en esa etapa.
- **Quedan afuera:**
  - `promociones`: el orden del complemento la pone en la etapa 6.
  - `reservas_stock`: necesita pedidos, que son de la etapa 3. Hasta entonces, disponible = stock actual.
  - `lotes_produccion` y `lote_insumos`: son de la etapa 4.
- **Cambios en el DBML:** van primero al modelo y después a las migraciones (ver puntos 7 y 8). Los del módulo 1 ya están en `docs/modelo-datos.dbml`:
  - `articulos.costo_actual` se reemplaza por la tabla `costos_articulo` (costo por artículo y ámbito).
  - `articulos.ambito_id`: el ámbito dueño del artículo. Es la fábrica para insumos y productos terminados, y un local para la reventa.
  - Valores válidos de unidad de medida y de alícuota de IVA.
  - La restricción de que la ubicación pertenezca al ámbito se agrega con el módulo 3.

## 2. Módulos, en orden

Cada módulo lleva su migración (V4 en adelante), sus tests de acceso y su resumen antes de pasar al siguiente.

1. **Catálogo:** categorías, artículos y costo por ámbito.
2. **Listas de precios:** listas, precios por artículo, historial, actualización masiva y margen.
3. **Stock:** ubicaciones, stock por ubicación con mínimo y máximo, movimientos, ajustes manuales y alertas bajo mínimo.
4. **Conteos físicos.**
5. **Catálogo público.** Va antes de entregas porque solo necesita los módulos 1 a 3.
6. **Entregas de la fábrica a los locales**, en la parte que no depende de la etapa 3.
7. **Importación desde planilla (CSV)** de artículos y de precios, con vista previa y errores por fila antes de aplicar (ver punto 7.9).

## 3. Clases, endpoints y permisos por módulo

Reglas generales, iguales a las de la etapa 1:

- El ámbito sale siempre de la sesión. Pedir por id un registro de otro ámbito devuelve 404.
- Cada servicio valida el acceso al principio con `Acceso`.
- Toda alta, edición, desactivación, cambio de precio, ajuste, cierre de conteo y paso de entrega se audita con el ámbito afectado.
- El administrador técnico no ve datos comerciales: no tiene acceso a ningún endpoint de esta etapa, salvo los públicos.
- El empleado nunca recibe costos, márgenes ni precios de entrega. Se usan DTOs separados, no campos en null.
- Nada se borra: se desactiva.

### Módulo 1: catálogo (paquete `catalogo`)

- **Clases:**
  - Entidades y repositorios: `Categoria`, `Articulo`, `TipoArticulo`, `CostoArticulo` (costo por ámbito, ver punto 7) y sus repositorios.
  - Servicios: `CategoriaService` y `ArticuloService`.
  - Controladores: `CategoriaController` y `ArticuloController`.
  - DTOs: `ArticuloResponse` (para dueños, con el costo de su ámbito) y `ArticuloEmpleadoResponse` (sin costo).
- **Endpoints:**
  - `GET /api/categorias` y `GET /api/articulos` (filtros: tipo, categoría, texto, activo), más `GET /api/articulos/{id}`.
  - `POST /api/categorias`, `PUT /api/categorias/{id}` y `POST /api/categorias/{id}/activar|desactivar`.
  - `POST /api/articulos`, `PUT /api/articulos/{id}` y `POST /api/articulos/{id}/activar|desactivar`.
  - `PUT /api/articulos/{id}/costo`: el costo del artículo en el ámbito de la sesión.
- **Qué artículos ve cada ámbito:**
  - La fábrica ve sus insumos y sus productos terminados. No ve la reventa de los locales.
  - Cada local ve los productos terminados de la fábrica y su propia reventa. No ve la reventa de otros locales ni los insumos de la fábrica.
  - Pedir por id un artículo que el ámbito no ve devuelve 404.
- **Permisos:**
  - Dueño de fábrica:
    - Administra las categorías, que son comunes a todos los ámbitos.
    - Administra los insumos y los productos terminados.
    - Ve y edita el costo de la fábrica. Hasta la etapa 4, ese costo se carga a mano.
  - Dueño de local:
    - Crea y edita los artículos de reventa de su local.
    - Carga a mano el costo de su reventa hasta que existan las compras (etapa 4).
    - Ve los productos terminados de la fábrica sin poder editarlos. Su costo en el local es el precio de la última entrega recibida (módulo 6) y no se carga a mano.
  - Empleado: consulta los mismos artículos que su dueño de local, solo los activos y sin costos.
- **Reglas:**
  - El tipo del artículo no cambia después del alta, porque define de qué ámbito es.
  - Los insumos no tienen categoría ni se muestran en la web. Los productos terminados y la reventa llevan categoría.
  - Una categoría desactivada no se puede asignar a artículos nuevos ni editados.
  - El nombre del artículo es único dentro de cada ámbito, sin distinguir mayúsculas. La importación CSV del módulo 7 lo necesita para encontrar el artículo de cada fila.

### Módulo 2: listas de precios (paquete `precios`)

- **Clases:**
  - Entidades y repositorios: `ListaPrecios`, `ListaPreciosItem` y `HistorialPrecio`.
  - Servicios: `ListaPreciosService` y `ActualizacionMasivaService`.
  - Controladores: `ListaPreciosController` y `PrecioEmpleadoController`.
- **Endpoints:**
  - **Listas:**
    - Consultar: `GET /api/listas-precios`, solo las de su ámbito.
    - Administrar: `POST /api/listas-precios`, `PUT /api/listas-precios/{id}` y `POST /api/listas-precios/{id}/activar|desactivar`.
  - **Precios de una lista:**
    - `GET /api/listas-precios/{id}/items`: precio, costo de su ámbito y margen.
    - `PUT /api/listas-precios/{id}/items/{articuloId}`: carga o cambia un precio.
    - `DELETE /api/listas-precios/{id}/items/{articuloId}`: saca el artículo de la lista; el historial lo conserva.
  - **Actualización masiva:** `POST /api/listas-precios/{id}/actualizacion-masiva`, con porcentaje, filtro opcional por categoría o tipo, y redondeo.
  - **Historial:** `GET /api/listas-precios/{id}/historial?articuloId=`.
  - **Empleado:** `GET /api/precios`, solo artículo y precio de la lista predeterminada de su local.
- **Permisos:**
  - El dueño de fábrica administra las listas de la fábrica, que son las listas para locales.
  - El dueño de local administra las listas de su local; la predeterminada es la de venta al público.
  - El empleado solo consulta los precios de venta de su local, que necesita para vender en la etapa 3.
- **Reglas:**
  - Cada cambio de precio guarda una fila en `historial_precios`, que es solo de inserción, igual que la auditoría.
  - El IVA depende de la condición fiscal de la entidad del ámbito dueño de la lista (ver punto 8):
    - Si la entidad discrimina IVA (responsable inscripto), la lista puede tener precios con o sin IVA, y el margen se calcula sobre el precio neto. Si la lista tiene precios con IVA, se divide por (1 + alícuota).
    - Si no lo discrimina (monotributo o exento), el precio de la lista es el precio final y el margen se calcula sobre él, sin sacar IVA. Esas listas se guardan siempre con `precios_con_iva = true`, y el servicio rechaza otro valor.
  - Margen = (precio − costo) / precio, con el costo del ámbito de la lista.
  - Tests: el margen de un mismo artículo en una lista de un responsable inscripto con IVA, en una sin IVA y en la de un monotributista.
  - Hay una sola lista predeterminada por ámbito, garantizada con un índice único parcial.

### Módulo 3: stock (paquete `stock`)

- **Clases:**
  - Entidades y repositorios: `UbicacionStock`, `StockArticulo`, `MovimientoStock` y `TipoMovimientoStock`.
  - `RegistroStock`: punto único para registrar movimientos, como `Auditor` para la auditoría. Lo usan ajustes, conteos y entregas, y después ventas, compras y producción.
  - Servicios: `UbicacionService` y `StockService`.
  - Controladores: `UbicacionController` y `StockController`.
- **Endpoints:**
  - **Ubicaciones:** `GET /api/ubicaciones`, `POST /api/ubicaciones`, `PUT /api/ubicaciones/{id}` y `POST /api/ubicaciones/{id}/activar|desactivar`.
  - **Consulta de stock:** `GET /api/stock`, con filtros por ubicación, artículo, tipo y bajo mínimo. Devuelve cantidad, mínimo y máximo.
  - **Límites:** `PUT /api/stock/limites`, con artículo, ubicación, mínimo y máximo.
  - **Ajustes:** `POST /api/stock/ajustes`, con artículo, ubicación, cantidad con signo y motivo obligatorio.
  - **Movimientos:** `GET /api/stock/movimientos`, paginado, con filtros por artículo, ubicación, tipo y fechas.
  - **Alertas:** `GET /api/stock/alertas`, con los artículos por debajo del mínimo.
- **Permisos:**
  - El dueño de fábrica opera el stock de la fábrica (insumos y producto terminado): ubicaciones, límites, ajustes y consulta.
  - El dueño de local opera el stock de su local: ubicaciones, límites, ajustes con motivo y consulta.
  - El empleado consulta el stock y los movimientos de su local, sin costos. No hace ajustes.
  - Nadie ve el stock de otro ámbito. En particular, la fábrica no ve el stock de los locales.

### Módulo 4: conteos físicos (paquete `stock`)

- **Clases:** `ConteoStock`, `DetalleConteo`, `ConteoService` y `ConteoController`.
- **Endpoints:**
  - **Abrir:** `POST /api/conteos`, con la ubicación.
  - **Consultar:** `GET /api/conteos` y `GET /api/conteos/{id}`.
  - **Cargar cantidades:** `PUT /api/conteos/{id}/items`, con las cantidades contadas.
  - **Cerrar:** `POST /api/conteos/{id}/cerrar`.
  - **Anular:** `POST /api/conteos/{id}/anular`, solo si está abierto.
- **Permisos:** abren, cargan y cierran el dueño de fábrica (en la fábrica) y el dueño de local (en su local). El empleado no participa (ver punto 7).
- **Reglas:**
  - Al cerrar, se toma la cantidad del sistema en ese momento.
  - Cada diferencia distinta de cero genera un movimiento de tipo `conteo`.
  - Un conteo cerrado no se modifica.
  - Solo puede haber un conteo abierto por ubicación.

### Módulo 5: catálogo público (paquete `catalogo`)

Ver punto 6.

### Módulo 6: entregas (paquete `entregas`)

- **Clases:** `Entrega`, `DetalleEntrega`, `EstadoEntrega`, `EntregaService` y `EntregaController`.
  - DTOs: `EntregaResponse` para los dueños, con precios, y `RecepcionEmpleadoResponse` para el empleado, sin precios.
- **Endpoints:**
  - **Enviar:** `POST /api/entregas`, con local destino, ubicación de origen, lista de precios y renglones (artículo, cantidad y lote opcional). Queda `enviada`.
  - **Consultar:** `GET /api/entregas` y `GET /api/entregas/{id}`. La fábrica ve las que envió; cada local, solo las que recibe.
  - **Anular:** `POST /api/entregas/{id}/anular`, solo si está `enviada` y antes de la recepción.
  - **Recibir:** `POST /api/entregas/{id}/recepcion`, con la ubicación de destino, la cantidad recibida por renglón y observaciones.
  - **Resolver diferencia:** `POST /api/entregas/{id}/resolver-diferencia`.
- **Permisos:**
  - El dueño de fábrica envía, anula y resuelve diferencias.
  - El dueño de local y el empleado registran la recepción; el complemento le da al empleado "registrar la recepción de mercadería".
  - El dueño de local ve los precios de la entrega; el empleado no, porque son el costo del local.

## 4. Cálculo del stock y cómo se evita que quede negativo

- **El stock es la suma de los movimientos.** Cada movimiento tiene:
  - artículo, ubicación y tipo
  - cantidad con signo (positiva si entra, negativa si sale)
  - usuario, fecha y, según el tipo, motivo, conteo, entrega o lote
- **Movimientos de esta etapa:** `ajuste`, `conteo`, `entrega_salida`, `entrega_recepcion`, `anulacion` (entrega anulada) y `devolucion` (diferencia que vuelve a la fábrica). Los demás tipos se usan desde la etapa 3.
- **`movimientos_stock` es solo de inserción**, con triggers como los de `auditorias`. Un error se corrige con otro movimiento, nunca editando.
- **`stock_articulos.cantidad_actual` es un caché que mantiene la base**, no la app:
  - Un trigger `AFTER INSERT` en `movimientos_stock` hace un upsert en `stock_articulos`, sumando la cantidad del movimiento.
  - Así el caché no puede desincronizarse aunque alguien inserte movimientos por fuera de la app.
  - En JPA, la columna se mapea como solo lectura (`insertable = false, updatable = false`). Después de registrar movimientos se vuelve a leer.
- **No puede quedar negativo:**
  - `stock_articulos` lleva `check (cantidad_actual >= 0)`.
  - Si un movimiento lo dejaría negativo, el trigger falla, se revierte la transacción entera y la API responde 409 "Stock insuficiente", con el artículo y la ubicación.
  - Si una entrega con varios renglones falla en uno, no se aplica ninguno.
- **Concurrencia:** el upsert bloquea la fila de `stock_articulos` hasta el final de la transacción. Dos ventas simultáneas del último artículo se ordenan solas: la segunda ve la cantidad ya descontada y falla.
- **Validación previa:** `RegistroStock` valida antes de insertar, para devolver un mensaje claro. La base sigue siendo la garantía final.
- **Desde la etapa 3:** disponible = cantidad actual − reservas activas, y las ventas y pedidos validan contra el disponible.
- **Control de consistencia:** un test de integración verifica que, para cada artículo y ubicación, `cantidad_actual` = suma de movimientos.
- **Cantidades:** `numeric(14,3)`. Si la unidad es `unidad`, se exige una cantidad entera.

## 5. Entregas de la fábrica a los locales

- **Entra en la etapa 2:**
  - El circuito físico completo, con los estados `enviada`, `recibida` y `diferencia_informada`.
  - Precio desde la lista para locales de la fábrica, guardado en cada renglón, con subtotal y total.
  - Stock que sale del origen al enviar (`entrega_salida`) y entra al destino al confirmar la recepción (`entrega_recepcion`), con la cantidad recibida.
  - El lote en el detalle y en los movimientos. Será null hasta que existan los lotes, en la etapa 4.
  - Anulación de una entrega enviada y no recibida, que devuelve el stock al origen.
  - Al confirmar la recepción, el costo del artículo en el local pasa a ser el precio unitario de esa entrega: el costo es el de la última entrega recibida (ver punto 8). Si la entidad del local discrimina IVA, el costo se guarda neto; si no, con el IVA incluido, porque para ella el IVA no se recupera.
- **Diferencias:**
  - Si la cantidad recibida es menor que la enviada, la entrega queda en `diferencia_informada`, con las observaciones del local.
  - La fábrica la resuelve de una de dos formas:
    - reingresando la diferencia a su stock (`devolucion`)
    - dándola por perdida, con motivo
  - En ambos casos queda `diferencia_resuelta_at`.
- **Queda para la etapa 3:**
  - El remito como comprobante: `remito_id`, `factura_id` y `nota_credito_id` se agregan con la tabla `comprobantes`.
  - El movimiento de cuenta corriente que genera la deuda del local.
  - La resolución de una diferencia con nota de crédito.
  - Las cobranzas.
- **Para no perder deuda:**
  - Las entregas recibidas en la etapa 2 tienen el total guardado.
  - La migración de la etapa 3 genera los movimientos de cuenta corriente de esas entregas, porque el saldo se calcula desde movimientos.
  - Alternativa más prudente: usar las entregas en producción recién desde la etapa 3, y en la etapa 2 solo en pruebas (ver punto 7).

## 6. Catálogo público

- **Endpoints sin login y de solo lectura:**
  - `GET /api/publico/categorias`
  - `GET /api/publico/catalogo?localId=&categoriaId=&q=`
  - `GET /api/publico/catalogo/{articuloId}?localId=`
  - `GET /api/publico/locales`, que ya existe.
- **Qué se muestra:**
  - Solo artículos activos, con `visible_web`, de tipo producto terminado o reventa, y con precio en la lista predeterminada de un local visible en la web.
  - Datos: nombre, descripción, categoría, marca, unidad, imagen y precio de venta al público del local elegido (punto 7.3).
  - El precio público con IVA solo se calcula si la entidad del local discrimina IVA: si su lista es sin IVA, el IVA se suma al responder. Si la entidad no discrimina IVA, se muestra el precio de la lista tal cual, porque es el precio final.
  - Tests: el precio público de un local responsable inscripto con lista sin IVA (se suma) y el de un local monotributista (sin cambios).
  - Disponibilidad como sí o no por local: disponible mayor que cero.
- **Qué no se expone nunca:**
  - Costos ni márgenes.
  - Cantidades de stock, mínimos, máximos ni ubicaciones.
  - Precios de otras listas, en particular la lista de la fábrica para locales.
  - Historial de precios, lotes ni movimientos.
  - Insumos, artículos inactivos u ocultos de la web, y locales no visibles.
  - Ningún dato fiscal.
- **Cómo se garantiza:**
  - DTOs propios (`ArticuloPublicoResponse`), nunca las entidades ni los DTOs internos.
  - Un test compara las claves exactas del JSON público contra una lista permitida, así un campo nuevo no se filtra sin querer.
  - Respuestas con `Cache-Control` corto (por ejemplo, 60 segundos), pensando en la meta de menos de 3 segundos del complemento.
- **El front:** `index.html` hoy tiene los productos y precios fijos en el código. Conectarlo a esta API sería una tarea aparte (ver punto 7).

## 7. Qué falta definir, con una propuesta para cada punto

1. **Costo por ámbito.**
   - El problema: `articulos.costo_actual` es uno solo, pero un mismo producto tiene un costo en la fábrica (el costo del lote) y otro en cada local (el precio que pagó a la fábrica). Además, si el local viera el costo de la fábrica, conocería su margen.
   - Propuesta:
     - Sacar `costo_actual` de `articulos` y crear `costos_articulo (articulo_id, ambito_id, costo_actual)`, con unicidad por artículo y ámbito.
     - Cada ámbito ve solo su costo.
2. **Quién administra el catálogo maestro.**
   - El problema: `articulos` y `categorias` son globales (no tienen ámbito), y el complemento le da al dueño de fábrica "productos de fábrica" y al dueño de local "catálogo de su local".
   - Propuesta:
     - Categorías, insumos y productos terminados los administra el dueño de fábrica.
     - Los artículos de reventa los puede crear cualquier dueño de local, y quedan con `ambito_creador_id` (columna nueva). Los edita ese ámbito o el dueño de fábrica.
     - Qué vende cada local lo define su lista de precios: un artículo sin precio en la lista predeterminada no se vende ni aparece en la web de ese local.
3. **Qué precio muestra la web.**
   - El problema: cada local tiene su lista de venta al público, y la web hoy muestra un único precio.
   - Propuesta: el visitante elige el local (o se usa el local central por defecto), y la web muestra el precio y la disponibilidad de ese local.
4. **Ubicaciones iniciales.**
   - Propuesta:
     - Al crear un ámbito se crea una ubicación "Principal".
     - La migración crea una para los ámbitos existentes.
     - La fábrica puede agregar otras, por ejemplo "Insumos" y "Producto terminado".
5. **Carga inicial del stock.**
   - Propuesta: hacerla con un conteo inicial por ubicación. Las cantidades contadas contra cero generan los movimientos de tipo `conteo`, sin inventar un tipo nuevo.
6. **Unidades de medida y alícuotas.**
   - Propuesta:
     - Unidad como `varchar` con `CHECK`: `unidad`, `kg`, `litro`, `m3` y `dm3`. Las bolsas de 80 dm³ se venden como `unidad`.
     - Alícuota de IVA con `CHECK` en 0, 10.5, 21 y 27.
7. **Variantes de la web.**
   - El problema: la web muestra "Coco Elite" y "Coco Elite Plus" como variantes de un producto, y en la etapa 1 se decidió eliminar los grupos y las variantes.
   - Propuesta: cada variante es un artículo propio. Si la web necesita agruparlas, lo hace por nombre o categoría.
8. **Imágenes de los artículos.**
   - Propuesta: en esta etapa, `imagen` guarda una ruta dentro de `assets/productos/`, con las imágenes que ya existen. La subida de archivos queda para cuando se conecte el front.
9. **Importación desde planilla.**
   - El complemento pide la actualización masiva de precios importando una planilla, y la carga inicial de productos desde planilla.
   - Propuesta: en esta etapa, solo la actualización por porcentaje. La importación CSV (de precios y de artículos) va como un módulo 7 opcional de esta etapa, o en la etapa 5 junto con las exportaciones.
   - **Decidido:** módulo 7 de esta etapa, al final. Primero muestra una vista previa con los errores de cada fila, y solo aplica los cambios cuando el usuario confirma.
10. **Deuda de las entregas con diferencia.**
    - Pregunta: ¿el local le debe a la fábrica lo enviado o lo recibido?
    - Propuesta: la deuda es por lo recibido. Si la fábrica no está de acuerdo, lo resuelve con una nota de crédito o un ajuste en la etapa 3.
11. **Entregas en producción antes de la etapa 3.**
    - Propuesta: habilitarlas en producción recién cuando exista la cuenta corriente, para que ninguna entrega quede sin deuda. En la etapa 2 se prueban con datos de prueba.
12. **Conteos y empleados.**
    - El complemento le da al empleado solo consulta y recepción de stock.
    - Propuesta: el empleado no hace conteos. Si conviene que cargue las cantidades contadas y el dueño cierre, se agrega después.
13. **Stock durante un conteo.**
    - Propuesta: la cantidad del sistema se toma al cerrar, no al abrir. Se recomienda contar con el local cerrado. No se bloquean las ventas durante el conteo.
14. **Front interno.**
    - Propuesta: como en la etapa 1, solo API y tests, sin conectar `privado/`.
    - El catálogo público queda listo para que `index.html` lo consuma. Esa conexión es una tarea aparte, porque `index.html` tiene cambios locales sin commitear.

### Decisiones sobre los puntos anteriores

- **1.** Aprobado: tabla `costos_articulo` por artículo y ámbito.
- **2.** Aprobado con un cambio:
  - El dueño de fábrica administra las categorías, los insumos y los productos terminados.
  - Cada dueño de local crea y edita su propia reventa.
  - La columna se llama `articulos.ambito_id` en lugar de `ambito_creador_id`, como en las demás tablas con dueño.
- **3.** Aprobado: cada local tiene su lista y el visitante elige el local, con el local central por defecto.
- **4 a 8:** aprobados tal como se propusieron.
- **9.** Importación CSV de artículos y de precios como módulo 7, al final de la etapa, con vista previa y errores por fila antes de aplicar.
- **10.** Aprobado: la deuda de una entrega es por lo recibido.
- **11 a 14:** aprobados tal como se propusieron.

## 8. Correcciones al plan

- **El IVA depende de la condición fiscal de la entidad del ámbito.**
  - Discrimina IVA solo el responsable inscripto: `CondicionIva.discriminaIva()`.
  - El margen sobre el precio neto y el precio público con IVA solo se aplican a esas entidades.
  - Para las demás, el precio de la lista es el precio final y el margen se calcula sobre él.
  - Se agrega en los módulos 2 y 5, con sus tests (ver punto 3, módulo 2, y punto 6).
- **Qué artículos ve un dueño de local:**
  - Ve los productos terminados de la fábrica y la reventa de su propio local.
  - No ve la reventa de otros locales.
  - Tests en el módulo 1: un dueño de local no ve, ni en el listado ni por id, la reventa de otro local.
- **El costo de un artículo en el local es el precio de la última entrega recibida.**
  - Hasta el módulo 6, los productos terminados no tienen costo en los locales.
  - El promedio ponderado se evalúa en la etapa 4, junto con las compras y el costo de los lotes.

## 9. Para revisar en la etapa 3

- **Ajustes de productos de la fábrica en un local.**
  - El problema: un dueño de local puede ajustar el stock de un producto terminado de la fábrica, por ejemplo sumarle unidades, sin que haya una entrega ni una deuda con la fábrica.
  - Evaluar cómo detectarlo: por ejemplo, un reporte de ajustes positivos de productos de la fábrica en cada local, una alerta para la fábrica o un límite a esos ajustes.
