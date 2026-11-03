# Resumen del módulo 3 de la etapa 2: stock

## 1. Qué se hizo

- **Modelo:** `docs/modelo-datos.dbml`, actualizado antes que la migración, con las reglas de `ubicaciones_stock`, `stock_articulos` y `movimientos_stock`.
- **Migración:** `V6__stock.sql`.
- **Paquete `stock`:**
  - Entidades: `UbicacionStock`, `StockArticulo` y `MovimientoStock`.
  - `TipoMovimientoStock`, con los 11 tipos del modelo.
  - Repositorios de las tres entidades.
  - `RegistroStock`: el único punto que registra movimientos.
  - Servicios: `UbicacionService` y `StockService`.
  - Controladores: `UbicacionController` y `StockController`.
  - `StockInsuficienteException`.
  - DTOs.
- **Cambios en otros módulos:**
  - `Articulo.visiblePara(ambito)` y `Articulo.esDe(ambito)`: la regla de qué artículos ve cada ámbito, que antes estaba repetida en catálogo y precios. Ahora la usan los tres módulos.
  - `ManejadorErrores`: si el CHECK de stock no negativo de la base rechaza un movimiento, responde 409 "Stock insuficiente."
  - `AuditoriaAtomicidadTest` (etapa 1): su limpieza ahora borra también la ubicación "Principal" que la base crea con cada ámbito.
  - `CLAUDE.md`: se agregó `stock` a la lista de paquetes.

## 2. La base de datos

### Ubicaciones

- Un trigger crea la ubicación "Principal" cada vez que se da de alta un ámbito. La migración la creó también para los ámbitos que ya existían.
- El nombre de la ubicación es único dentro del ámbito, sin distinguir mayúsculas.

### Stock por ubicación

- `stock_articulos` tiene un mínimo y un máximo opcionales: no negativos, y el máximo no puede ser menor que el mínimo.
- `cantidad_actual` es un caché de la suma de los movimientos:
  - Lo mantiene un trigger `AFTER INSERT` de `movimientos_stock`. Primero intenta un UPDATE y, si la fila no existe, la inserta.
  - El CHECK `stock_articulos_sin_negativo` impide que quede negativo.
  - Otro trigger rechaza cualquier INSERT o UPDATE directo que toque la cantidad. Solo la puede cambiar el trigger de movimientos, que corre a profundidad 2 (`pg_trigger_depth()`). El mínimo y el máximo sí se editan.
  - En JPA, la columna es de solo lectura (`insertable = false, updatable = false`). Después de registrar movimientos, la cantidad se vuelve a leer con una consulta de la base.
- **Error que encontraron los tests:** la primera versión del trigger usaba `INSERT ... ON CONFLICT`. PostgreSQL controla el CHECK sobre la fila propuesta (la cantidad del movimiento) antes de resolver el conflicto, así que rechazaba toda salida. Ahora el trigger hace primero el UPDATE y deja el `ON CONFLICT` solo para cuando dos transacciones insertan la primera fila a la vez.

### Movimientos

- Las cantidades son `numeric(14,3)`, nunca cero, y enteras si el artículo se mide en unidades (trigger).
- Todo ajuste exige motivo: `check (tipo <> 'ajuste' or coalesce(length(trim(motivo)), 0) > 0)`. El `coalesce` está porque, sin él, un motivo nulo da NULL y el CHECK lo deja pasar.
- **Solo de inserción:** triggers que rechazan UPDATE, DELETE y TRUNCATE, como en `auditorias`. La entidad es `@Immutable` y el repositorio solo tiene `save`.
- **Qué artículos puede tener cada ámbito:** solo los que ve. Es decir, los suyos y, en un local, los productos terminados de la fábrica. Esta es la restricción "la ubicación pertenece al ámbito del artículo" que el plan dejaba para el módulo 3. Se valida en los movimientos y en `stock_articulos`.
- **Columnas que todavía no están:** `movimientos_stock` se creó sin `venta_id`, `pedido_id` y `compra_id`, como decía el plan. Tampoco tiene `conteo_id` ni `entrega_id`, porque sus tablas todavía no existen; se agregan con los módulos 4 y 6.
- `lote_id` va sin FK hasta la etapa 4.

## 3. Endpoints

- **Ubicaciones:**
  - `GET /api/ubicaciones`.
  - `POST /api/ubicaciones` y `PUT /api/ubicaciones/{id}`, con `nombre`.
  - `POST /api/ubicaciones/{id}/activar` y `POST /api/ubicaciones/{id}/desactivar`.
- **Consulta de stock:** `GET /api/stock`, con filtros `ubicacionId`, `articuloId`, `tipo` y `bajoMinimo`.
  - Devuelve artículo, tipo, unidad, ubicación, cantidad, mínimo, máximo y `bajoMinimo`.
- **Límites:** `PUT /api/stock/limites`, con `articuloId`, `ubicacionId`, `stockMinimo` y `stockMaximo`.
- **Ajustes:** `POST /api/stock/ajustes`, con `motivo` (obligatorio), `observaciones` y `renglones`.
  - Cada renglón tiene `articuloId`, `ubicacionId` y `cantidad`, con signo.
  - Responde 201 con el movimiento de cada renglón y la cantidad que quedó.
- **Movimientos:** `GET /api/stock/movimientos`, paginado.
  - Filtros: `articuloId`, `ubicacionId`, `tipo`, `desde` y `hasta`.
  - `desde` y `hasta` son días completos en la zona horaria de la empresa, como en la auditoría.
  - `pagina` empieza en 0; `tamano` es 50 por defecto, con un máximo de 200.
- **Alertas:** `GET /api/stock/alertas`, con los artículos por debajo del mínimo.

## 4. Permisos

- **Dueño de fábrica y dueño de local:** operan el stock de su ámbito:
  - ubicaciones,
  - límites,
  - ajustes,
  - consulta, movimientos y alertas.
- **Empleado de local:**
  - Consulta el stock, los movimientos, las alertas y las ubicaciones de su local.
  - En el stock y en las ubicaciones ve solo lo activo.
  - Las respuestas de stock no tienen costos ni precios. Es el mismo DTO que el del dueño, porque el stock no tiene campos de costo.
  - No hace ajustes ni cambia límites, y no administra ubicaciones: recibe 403.
- **Otros ámbitos:**
  - Nadie ve el stock de otro ámbito. En particular, la fábrica no ve el de los locales.
  - Filtrar por una ubicación de otro ámbito devuelve una lista vacía.
  - Operar sobre una ubicación o un artículo de otro ámbito responde 404.
- **Administrador técnico:** recibe 403 en todo el módulo.
- **Sin sesión:** 401.

## 5. Ajustes, concurrencia y stock insuficiente

- **Motivo obligatorio:**
  - Si falta o está en blanco, la API responde 400 y la base lo rechaza aunque se inserte por fuera de la aplicación.
  - Cada movimiento lleva el motivo.
- **Auditoría:**
  - Un registro `ajustar_stock` por cada ajuste, con la entidad `movimiento_stock` y el ámbito afectado.
  - El detalle lleva el motivo, las observaciones y cada renglón: movimiento, artículo, ubicación y cantidad.
  - Los límites se auditan como `editar_limites`, solo con los campos que cambiaron. Las ubicaciones, como `crear`, `editar`, `activar` y `desactivar`.
- **Todo o nada:**
  - `RegistroStock` corre siempre dentro de la transacción de la operación (propagación MANDATORY).
  - Si un renglón falla, por stock insuficiente, cantidad cero, decimales en algo que se mide en unidades o un artículo o ubicación que el ámbito no ve, se revierte toda la operación, incluidos los renglones ya registrados.
- **Concurrencia:**
  - Antes de una salida, `RegistroStock` bloquea la fila de stock con `SELECT ... FOR UPDATE` y valida que alcance.
  - Si dos operaciones sacan a la vez el último artículo, la segunda espera a que la primera termine, ve la cantidad ya descontada y falla con 409 "Stock insuficiente: 'Maceta' en 'Principal' tiene 0 y se quieren sacar 1."
  - Las filas se bloquean siempre en el mismo orden (artículo y ubicación), para que dos operaciones con varios renglones no se traben entre sí.
- **Garantía final:** el CHECK de la base. Si algo pasara la validación previa, el movimiento falla igual, se revierte todo y la API responde 409 "Stock insuficiente."

## 6. Tests nuevos (24)

### `StockMigracionTest` (lo que garantiza la base, 8)

- `losMovimientosNoSeModificanNiSeBorran`: la base rechaza UPDATE, DELETE y TRUNCATE sobre `movimientos_stock`, y el movimiento queda igual.
- `cadaMovimientoSumaSuCantidadAlStockDeSuArticuloYUbicacion`: el trigger suma entradas y salidas, por separado en cada ubicación.
- `laCantidadNoSeEditaAMano`: un UPDATE o INSERT directo de la cantidad falla; el mínimo y el máximo sí se editan.
- `elStockNoPuedeQuedarNegativo`: el CHECK rechaza una salida mayor que el stock, también cuando todavía no hay fila.
- `todoAjusteExigeMotivoYNingunMovimientoEsCero`: un ajuste con motivo nulo o en blanco falla, igual que una cantidad cero; los otros tipos no exigen motivo.
- `loQueSeMideEnUnidadesSeMueveEnCantidadesEnteras`: 1,5 unidades falla; 1,5 kg se acepta.
- `cadaAmbitoTieneStockSoloDeLosArticulosQueVe`: un local no tiene stock de insumos ni de la reventa de otro local, y la fábrica no tiene stock de la reventa.
- `cadaAmbitoNaceConLaUbicacionPrincipalYLosNombresSonUnicosEnElAmbito`: el trigger crea "Principal" con cada ámbito, y los nombres repetidos se rechazan sin distinguir mayúsculas.

### `StockApiTest` (API y acceso, 13)

- `unAjusteConMotivoMueveElStockYQuedaEnAuditoriaConElAmbitoAfectado`: entradas y salidas, la cantidad que queda, los movimientos con su motivo y un registro de auditoría por ajuste con el ámbito afectado.
- `todoAjusteExigeUnMotivo`: sin motivo o con motivo en blanco responde 400, sin movimientos ni auditoría.
- `unAjusteDeVariosRenglonesQueFallaEnUnoNoAplicaNinguno`: con stock insuficiente, un artículo que el local no ve o una cantidad cero en un renglón, no se aplica ninguno.
- `loQueSeMideEnUnidadesSeAjustaEnCantidadesEnteras`: 1,5 unidades responde 409.
- `noSeAjustaEnUnaUbicacionDesactivada`: responde 409.
- `elDuenoFijaMinimoYMaximoYVeLasAlertasBajoMinimo`: límites, validación de que el máximo no sea menor que el mínimo, alertas, filtros de la consulta y auditoría de los límites.
- `losLimitesDeUnArticuloSinMovimientosDejanSuStockEnCero`: crea la fila con cantidad cero y la marca bajo mínimo.
- `elEmpleadoConsultaElStockYLosMovimientosDeSuLocalSinCostosYNoHaceAjustes`:
  - Recibe exactamente los campos permitidos del stock, y los movimientos no mencionan costo ni precio.
  - Ve alertas y ubicaciones.
  - Recibe 403 en ajustes, límites y administración de ubicaciones.
- `nadieVeNiTocaElStockDeOtroAmbito`:
  - La fábrica ve solo su stock, y cada local (dueño o empleado) solo el suyo, también al filtrar por una ubicación ajena.
  - Ajustes, límites y ubicaciones de otro ámbito responden 404.
- `elAdministradorTecnicoNoVeStock`: responde 403.
- `sinSesionResponde401`: responde 401.
- `elDuenoAdministraLasUbicacionesDeSuAmbitoYNoDesactivaUnaConStock`: alta, nombre único, edición y desactivación, con auditoría y ámbito afectado; desactivar una ubicación con stock responde 409.
- `paraCadaArticuloYUbicacionLaCantidadActualEsLaSumaDeLosMovimientos`: después de varios ajustes, en varias ubicaciones y ámbitos, uno rechazado y límites sin movimientos, compara en toda la base `cantidad_actual` con la suma de los movimientos de cada artículo y ubicación, sin diferencias.

### `StockConcurrenciaTest` (transacciones reales, 3)

Estos tests no corren dentro de la transacción del test. Al terminar borran lo que guardaron, con los triggers desactivados (`session_replication_role = replica`), porque los movimientos son solo de inserción.

- `siDosOperacionesSacanElUltimoArticuloALaVezUnaFuncionaYLaOtraFallaCon409`:
  - Una transacción saca el último artículo y mantiene el bloqueo.
  - Un ajuste por la API queda esperando (el test verifica que no termina en 500 ms).
  - Cuando la primera confirma, el ajuste responde 409 "Stock insuficiente". Quedan cantidad 0 y dos movimientos.
- `dosAjustesSimultaneosPorLaApiSobreElUltimoArticulo`: dos dueños largan a la vez el mismo ajuste por la API; uno responde 201 y el otro 409 "Stock insuficiente".
- `unAjusteQueFallaEnUnRenglonRevierteLosQueYaSeHabianRegistrado`: el renglón que entra se registra antes que el que falla, y después de la respuesta 409 no queda ni su movimiento ni la auditoría.
- Los corrí cinco veces seguidas y pasaron todas.

### Resultado

- Pasan los 181 tests del backend: los 157 anteriores y los 24 nuevos, sin fallas.

## 7. Decisiones que no estaban en el plan

- **Ajustes de varios renglones:** el plan describía un ajuste de un artículo. Lo amplié a varios renglones, de hasta 200, con un motivo común y un solo registro de auditoría. Así se puede probar el "todo o nada" que pediste y queda listo para conteos y entregas.
- **Ubicación "Principal":** la crea un trigger de la base, no el servicio de ámbitos. Así también la reciben los ámbitos que ya existían o que se crean por fuera de la app. No tiene un registro de auditoría propio: queda cubierta por el alta del ámbito.
- **Ubicaciones desactivadas:**
  - Una ubicación con stock no se puede desactivar (409).
  - En una ubicación desactivada no se hacen ajustes ni se fijan límites (409), pero su stock y sus movimientos se pueden consultar.
- **Artículos desactivados:** se pueden ajustar, por ejemplo para dejar su stock en cero. El empleado no los ve en la consulta de stock.
- **Bajo mínimo:** la cantidad tiene que ser estrictamente menor que el mínimo. Las alertas muestran solo artículos y ubicaciones activos, y también las ve el empleado.
- **Límites:** fijarlos crea la fila de stock con cantidad cero si todavía no había movimientos.
- **Cantidad bloqueada para edición:** la base no deja editar la cantidad del caché a mano. El plan solo decía que la mantiene la base.
- **Orden de bloqueo:** las filas se bloquean siempre por artículo y ubicación.
- **Mensaje de stock insuficiente:** nombra el artículo, la ubicación, la cantidad que hay y la que se quiere sacar.
- **Nombres de auditoría:**
  - `ajustar_stock`, con la entidad `movimiento_stock` y, como id, el del primer movimiento.
  - `editar_limites`, con la entidad `stock_articulo`.
  - `crear`, `editar`, `activar` y `desactivar`, con la entidad `ubicacion_stock`.
