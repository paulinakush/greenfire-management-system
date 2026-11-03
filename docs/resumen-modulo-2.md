# Resumen del módulo 2 de la etapa 2: listas de precios

## 1. Test del empleado sin costos (módulo 1)

- `ArticuloApiTest.elEmpleadoVeLosArticulosActivosDeSuLocalSinCostos`.
  - Exige que cada artículo del listado del empleado tenga exactamente estos campos: `id`, `tipo`, `nombre`, `descripcion`, `categoriaId`, `categoriaNombre`, `marca`, `unidadMedida`, `imagen` y `alicuotaIva`. Si se agrega un campo, el test falla.
  - Verifica que `$.costo` no aparezca al pedir el artículo por id.

## 2. Cómo quedó el módulo 2

### Archivos

- **Modelo:** `docs/modelo-datos.dbml`, actualizado antes que la migración.
- **Migración:** `V5__listas_precios.sql`, con las tablas `listas_precios`, `lista_precios_items` y `historial_precios`.
- **Paquete `precios`:**
  - Entidades: `ListaPrecios`, `ListaPreciosItem` y `HistorialPrecio`.
  - Repositorios de esas tres entidades.
  - Servicios: `ListaPreciosService` y `ActualizacionMasivaService`.
  - Controladores: `ListaPreciosController` y `PrecioEmpleadoController`.
  - `CalculoPrecio`: las cuentas de IVA, margen y redondeo.
  - DTOs.
- **Otros cambios:**
  - `CondicionIva.discriminaIva()`, que devuelve verdadero solo para el responsable inscripto.
  - `PUT /api/admin/entidades-fiscales/{id}` devuelve una advertencia si cambia la condición frente al IVA y la entidad tiene listas de precios (ver más abajo).

### Endpoints

- **Listas:**
  - `GET /api/listas-precios`: las listas del ámbito de la sesión.
  - `POST /api/listas-precios`: alta, con `nombre`, `preciosConIva` y `esPredeterminada`.
  - `PUT /api/listas-precios/{id}`: edición, con los mismos campos.
  - `POST /api/listas-precios/{id}/activar` y `POST /api/listas-precios/{id}/desactivar`.
- **Precios de una lista:**
  - `GET /api/listas-precios/{id}/items`: precio, precio neto, costo del ámbito y margen de cada artículo.
  - `PUT /api/listas-precios/{id}/items/{articuloId}`: carga o cambia un precio.
  - `DELETE /api/listas-precios/{id}/items/{articuloId}`: saca el artículo de la lista. Responde 204, o 404 si el artículo no tenía precio.
- **Actualización masiva:** `POST /api/listas-precios/{id}/actualizacion-masiva`, con `porcentaje`, `categoriaId`, `tipo` y `redondeo`.
- **Historial:** `GET /api/listas-precios/{id}/historial`, paginado, con `articuloId` (opcional), `pagina` (por defecto 0) y `tamano` (por defecto 50, máximo 200).
- **Precios de venta del local:** `GET /api/precios`. Devuelve solo `articuloId`, `articuloNombre` y `precio`.

### Permisos por perfil

- **Dueño de fábrica:**
  - Administra las listas de la fábrica, que son las listas para locales.
  - Recibe 403 en `GET /api/precios`.
- **Dueño de local:**
  - Administra las listas de su local.
  - Puede consultar `GET /api/precios`.
- **Empleado de local:**
  - Solo puede consultar `GET /api/precios`.
  - Recibe 403 en todos los endpoints de `/api/listas-precios`.
  - Nunca recibe costos ni márgenes, porque tiene un DTO propio (`PrecioEmpleadoResponse`).
- **Administrador técnico:** recibe 403 en todo el módulo.
- **Sin sesión:** 401.
- **Listas de otro ámbito:** responden 404 en todos los endpoints, igual que una lista inexistente. El ámbito sale siempre de la sesión.

### Margen y precio con IVA según la condición fiscal

- **La condición que se usa es la de la entidad fiscal del ámbito dueño de la lista.** Solo el responsable inscripto discrimina IVA.
- **Si la entidad discrimina IVA (responsable inscripto):**
  - La lista puede tener precios con IVA o sin IVA.
  - Precio neto: si la lista es con IVA, es el precio dividido por (1 + alícuota del artículo). Si es sin IVA, es el precio tal cual.
  - El margen se calcula sobre el precio neto.
- **Si no lo discrimina (monotributo, exento o consumidor final):**
  - El precio de la lista es el precio final.
  - La lista va siempre con IVA. Lo rechaza el servicio con 409 y, además, un trigger de la base.
  - El margen se calcula sobre el precio, sin sacarle IVA.
- **Fórmula del margen:**
  - Margen = (precio neto − costo) / precio neto.
  - Se devuelve como fracción con 4 decimales: 0,4000 es 40 %.
  - El costo es el del ámbito de la lista, de `costos_articulo`. Un local nunca usa el costo de la fábrica.
  - Si el ámbito no tiene costo del artículo, el margen es null. Es el caso de los productos terminados en los locales hasta el módulo 6.
- **Precio final, en `GET /api/precios`:** si la entidad discrimina IVA y la lista es sin IVA, se le suma el IVA. En los demás casos es el precio de la lista.
- **Ejemplo:** un artículo con IVA del 21 %, precio 1210 y costo 600.
  - Responsable inscripto, lista con IVA: neto 1000, margen 0,40.
  - Responsable inscripto, lista sin IVA: neto 1210, margen 0,5041.
  - Monotributista: neto 1210 (es el precio final), margen 0,5041.

### Actualización masiva

- **Cálculo:** el precio nuevo es el precio actual × (1 + porcentaje / 100).
  - El porcentaje acepta hasta 2 decimales y debe ser mayor que −100 y como mucho 1000.
- **Filtros opcionales:**
  - `categoriaId`: solo los artículos de esa categoría.
  - `tipo`: `producto_terminado` o `reventa`.
  - Si se mandan los dos, se aplican juntos.
  - Sin filtros, se cambia toda la lista.
- **Redondeo:**
  - Cada precio nuevo va al múltiplo de `redondeo` más cercano, por ejemplo 1, 10 o 50. La mitad redondea hacia arriba.
  - Si no se indica, se redondea al centavo.
  - Ejemplo: 1234 + 10 % = 1357,40, que con redondeo 10 queda en 1360.
- **Todo o nada:** si algún precio quedaría en cero o menos, responde 409 y no cambia ninguno.
- **Respuesta:** la cantidad de precios que cambiaron. Los que quedan iguales después del redondeo no cuentan.
- **Registros:**
  - Una fila en el historial por cada precio que cambió.
  - Un solo registro de auditoría (`actualizacion_masiva`), con el porcentaje, los filtros, el redondeo y la cantidad de precios cambiados.
- **Lista desactivada:** responde 409.

### Historial de precios

- **Qué guarda cada fila:** lista, artículo, precio anterior, precio nuevo, usuario y fecha.
  - Al agregar un artículo a la lista, el precio anterior es null.
  - Al sacarlo de la lista, el precio nuevo es null.
- **Solo de inserción:**
  - La base rechaza UPDATE, DELETE y TRUNCATE con triggers, igual que en `auditorias`.
  - La entidad es `@Immutable`.
  - El repositorio solo tiene `save` y la consulta.
- **Consulta:** ordenada de la más reciente a la más vieja, con filtro opcional por artículo.
- **Auditoría:**
  - Cada cambio individual también queda en la auditoría: `editar_precio` al cargar o cambiar un precio y `quitar_precio` al sacarlo.
  - El detalle lleva `articuloId` y el precio antes y después.
  - El ámbito afectado es el de la lista.
- **Mismo precio:** guardar un precio igual al que ya está no deja historial ni auditoría.

### Cambio de condición fiscal de una entidad con listas

- **No se recalcula ni se bloquea:** los precios de las listas quedan como están.
  - Desde ese momento, el margen y el precio final se calculan con la nueva condición.
  - Por ejemplo, si un responsable inscripto pasa a monotributo, el precio de una lista sin IVA pasa a ser el precio final.
- **Advertencia en la respuesta:** la respuesta de la edición trae los mismos campos de siempre, más `advertencia`.
  - Contiene un mensaje y las listas afectadas, con id, nombre, ámbito y si son con IVA. No incluye precios.
  - Es null si la condición no cambió o si la entidad no tiene listas.
- **Auditoría:** queda el registro `editar` de la entidad fiscal, con la condición anterior y la nueva, y un registro por cada ámbito de la entidad.
- **Trigger de V5:**
  - La regla de que una lista va con IVA si la entidad no lo discrimina se valida solo al crear la lista o al cambiar su ámbito o si es con IVA.
  - Así, una lista sin IVA que quedó de antes del cambio se puede seguir editando: renombrarla, activarla o desactivarla.
  - Corregí V5 en lugar de agregar una V6 porque no tenía commit y nunca se aplicó fuera de los tests. La base local tiene aplicadas solo V1 a V3.

## 3. Tests nuevos del módulo 2 (31)

### `CalculoPrecioTest` (unitario, 7)

- `unResponsableInscriptoConPreciosConIvaCalculaElMargenSobreElNeto`: 1210 con IVA del 21 % da un neto de 1000 y un margen de 0,40.
- `unResponsableInscriptoConPreciosSinIvaUsaElPrecioComoNeto`: en una lista sin IVA, el precio ya es neto.
- `siLaEntidadNoDiscriminaIvaElPrecioEsFinalYNoSeLeSacaIva`: con un monotributista no se descuenta IVA.
- `laAlicuotaDeDiezYMedioTambienSeDescuenta`: 1105 con IVA del 10,5 % da un neto de 1000.
- `sinCostoNoHayMargen`: sin costo, el margen es null.
- `elPrecioFinalSumaElIvaSoloSiLaEntidadLoDiscriminaYLaListaEsSinIva`: el IVA se suma solo en ese caso.
- `redondeaAlMultiploMasCercano`: redondeo al centavo, a 10 y a 50.

### `PreciosMigracionTest` (restricciones de la base, 4)

- `siLaEntidadNoDiscriminaIvaLaListaVaConIva`: el trigger rechaza una lista sin IVA de un monotributista y acepta una de un responsable inscripto.
- `hayUnaSolaListaPredeterminadaPorAmbitoYNoSePuedeDesactivar`: el índice único parcial y el check de que la predeterminada esté activa.
- `unaListaNoTienePreciosDeInsumosNiDeLaReventaDeOtroLocal`: el trigger de artículos válidos y que el precio sea mayor que cero.
- `elHistorialDePreciosNoSeModificaNiSeBorra`: la base rechaza UPDATE, DELETE y TRUNCATE.

### `ListaPreciosApiTest` (API y acceso, 20)

- `elMargenDeUnMismoArticuloDependeDeLaCondicionFiscalYDeSiLaListaEsConIva`: con el mismo artículo, el margen da 0,40 en un responsable inscripto con lista con IVA y 0,5041 en uno sin IVA y en un monotributista.
- `siCambiaLaCondicionFiscalLosPreciosQuedanComoEstanConAdvertenciaYAuditoria`:
  - Al pasar de responsable inscripto a monotributo, la respuesta trae las dos listas de la entidad, y no la de otra entidad.
  - La auditoría guarda solo la condición, con el valor anterior y el nuevo, y el ámbito afectado.
  - Los precios no cambian, y el de la lista sin IVA pasa a ser el final.
  - La lista sin IVA se sigue pudiendo editar.
- `sinCambioDeCondicionFiscalNoHayAdvertencia`: si cambia otro campo de la entidad, `advertencia` es null.
- `siLaEntidadNoDiscriminaIvaLaListaNoPuedeSerSinIva`: el servicio responde 409 al monotributista y deja crear la lista al responsable inscripto.
- `sinCostoDelAmbitoNoHayMargenYElLocalNoUsaElCostoDeLaFabrica`: el local no ve el costo de la fábrica y el margen es null.
- `cadaDuenoAdministraLasListasDeSuAmbitoYQuedanEnAuditoria`: alta y edición, con auditoría de solo los campos que cambiaron y con el ámbito afectado.
- `elNombreDeLaListaEsUnicoEnElAmbito`: un nombre repetido en el mismo ámbito, sin distinguir mayúsculas, da 409; en otro ámbito se permite.
- `hayUnaSolaPredeterminadaPorAmbitoYNoSePuedeDesactivar`: al marcar otra lista, la anterior deja de ser la predeterminada; la predeterminada no se desactiva.
- `siLaListaYaTienePreciosNoSeCambiaSiSonConOSinIva`: responde 409.
- `losPreciosDeUnaListaDesactivadaNoSeCambian`: responde 409.
- `nadieVeNiTocaLasListasDeOtroAmbito`: listado, precios, historial, edición, desactivación y actualización masiva sobre una lista de otro ámbito responden 404.
- `unaListaTienePreciosDeLoQueVendeSuAmbito`:
  - Los insumos no llevan precio (409).
  - La reventa de otro local responde 404.
  - Un artículo desactivado no admite cambios de precio (409).
- `cadaCambioDePrecioQuedaEnElHistorialYEnLaAuditoria`:
  - Carga, cambio y baja de precios, con el historial filtrado por artículo y en orden.
  - Un precio igual no deja registro.
  - Sacar dos veces el mismo artículo responde 404.
- `laActualizacionMasivaCambiaLosPreciosFiltradosRedondeaYAuditaUnaVez`: filtra por categoría, redondea a 10, guarda el historial y deja un solo registro de auditoría.
- `siAlgunPrecioQuedariaEnCeroNoSeAplicaNinguno`: si un precio quedaría en cero responde 409; un porcentaje de −100 responde 400.
- `elEmpleadoSoloVeArticuloYPrecioDeVentaDeSuLocal`:
  - Recibe los campos exactos, solo de la lista predeterminada y sin artículos inactivos.
  - Recibe 403 en todos los endpoints de `/api/listas-precios`.
- `siElLocalDiscriminaIvaYLaListaEsSinIvaElEmpleadoVeElPrecioConIva`: un precio de 1000 en lista sin IVA le aparece como 1210.
- `sinListaPredeterminadaElEmpleadoNoVePrecios`: recibe una lista vacía.
- `elAdministradorTecnicoNoVePrecios`: el administrador técnico recibe 403, y el dueño de fábrica también en `/api/precios`.
- `sinSesionResponde401`: `/api/listas-precios` y `/api/precios` sin sesión responden 401.

### Resultado

- Pasan los 157 tests del backend: los 126 anteriores y los 31 nuevos, sin fallas.

## 4. Decisiones que no estaban en el plan

### Aceptadas

- **Redondeo de la actualización masiva:** al múltiplo más cercano del valor indicado; sin valor, al centavo.
- **Actualización masiva de todo o nada:** si algún precio quedaría en cero, no se aplica ninguno.
- **Auditoría de la actualización masiva:** un solo registro, más una fila de historial por cada precio que cambió.
- **Rango del porcentaje:** mayor que −100 y como mucho 1000, con hasta 2 decimales.
- **Inactivos en la actualización masiva:** también cambia los precios de los artículos desactivados que siguen en la lista, porque si se reactivan conviene que tengan el precio al día.
- **Con o sin IVA:** solo se cambia mientras la lista no tiene precios, porque si no cambiaría el sentido de todos los que ya tiene.
- **Lista predeterminada:**
  - Al marcar una como predeterminada, la anterior deja de serlo sola, y ese cambio también queda en la auditoría.
  - Un ámbito puede quedarse sin lista predeterminada.
  - Una lista desactivada no puede ser predeterminada, y la predeterminada no se puede desactivar. Lo garantiza un check en la base.
- **Insumos:** no llevan precio en ninguna lista, tampoco en las de la fábrica. Lo garantiza un trigger en la base.
- **Artículos desactivados:**
  - No se les carga ni se les cambia el precio.
  - Los precios que ya tenían quedan en la lista.
  - El empleado no los ve.
- **Lista desactivada:** sus precios se consultan pero no se cambian.
- **Precio:** debe ser mayor que cero. Lo garantiza un check en la base.
- **`GET /api/precios`:**
  - Devuelve el precio final que paga el consumidor: suma el IVA si la entidad lo discrimina y la lista es sin IVA.
  - También lo puede consultar el dueño de local.
  - Sin lista predeterminada, devuelve una lista vacía.
- **Historial paginado:** con `pagina` y `tamano`, máximo 200 por página, igual que la auditoría.
- **Nombres de las acciones de auditoría:** `crear`, `editar`, `activar`, `desactivar`, `editar_precio`, `quitar_precio` y `actualizacion_masiva`, todas con la entidad `lista_precios`.

### Respondida: cambio de condición fiscal

- Al cambiar la condición frente al IVA de una entidad con listas de precios, no se recalculan los precios ni se bloquea el cambio.
- La respuesta advierte qué listas quedaron afectadas, y la auditoría guarda el valor anterior y el nuevo.
- El detalle está en el punto 2, en "Cambio de condición fiscal de una entidad con listas".
