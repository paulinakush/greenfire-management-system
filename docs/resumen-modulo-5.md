# Resumen del módulo 5 de la etapa 2: catálogo público

## 1. Qué se hizo

- **Modelo:** `docs/modelo-datos.dbml`, actualizado antes que la migración. `ambitos` suma `es_central`.
- **Migración:** `V8__local_central.sql`, una migración nueva. V1 a V7 no se tocaron.
- **Paquete `catalogo`:**
  - `CatalogoPublicoService` y `CatalogoPublicoController`.
  - DTOs propios: `CategoriaPublicaResponse`, `ArticuloPublicoResponse` y `CatalogoPublicoResponse` (la página).
- **Paquete `ambitos`:**
  - `Ambito.esCentral`.
  - `AmbitoService.marcarCentral`, con su endpoint de administrador.
  - `LocalPublicoResponse` suma `id`, para elegir el local en el catálogo, y `esCentral`.
  - `AmbitoResponse` (la del administrador) suma `esCentral`.
- **Consultas:**
  - `ListaPreciosItemRepository.catalogoPublico` (paginada) y `articuloPublico`.
  - `StockArticuloRepository.conStock`, que solo dice qué artículos tienen stock, nunca cuánto.
- **Paquete `comun`:**
  - `CachePublico`, el `Cache-Control` común a los endpoints públicos.
  - `SolicitudInvalidaException`, que responde 400.
  - `ManejadorErrores` traduce las dos restricciones nuevas de la base a 409 con un mensaje claro.

## 2. La base de datos (V8)

- **`ambitos.es_central`:** `boolean not null default false`.
- **`ambitos_central_valido`:** un CHECK que exige que el central sea un local, activo y visible en la web.
  - La fábrica no puede ser central.
  - El central no se puede ocultar ni desactivar: primero hay que marcar otro.
- **`uq_ambitos_central`:** un índice único parcial que no deja haber dos locales centrales.
- **Datos existentes:** V8 no marca ningún local. Hasta que el administrador técnico elija uno, el catálogo sin `localId` responde 400.

## 3. Endpoints

- **Públicos, sin login y de solo lectura:**
  - `GET /api/publico/categorias`: las categorías activas, con `id`, `nombre` y `descripcion`.
  - `GET /api/publico/catalogo?localId=&categoriaId=&q=&pagina=&tamano=`: una página del catálogo del local.
    - Trae `localId`, `localNombre`, `articulos`, `pagina`, `tamano` y `total`.
  - `GET /api/publico/catalogo/{articuloId}?localId=`: un artículo.
    - Trae `id`, `nombre`, `descripcion`, `categoriaId`, `categoriaNombre`, `marca`, `unidadMedida`, `imagen`, `precio` y `disponible`.
  - `GET /api/publico/locales`, que ya existía: ahora suma `id` y `esCentral`.
  - POST, PUT y DELETE sobre esas rutas responden 405.
- **Del administrador técnico:**
  - `POST /api/admin/ambitos/{id}/central` marca el local central y desmarca al anterior.
  - El dueño de fábrica y el dueño de local reciben 403; sin sesión, 401.

## 4. Cómo funciona

### Qué artículos aparecen

- **Requisitos del artículo:** tiene que estar activo y visible en la web, ser de tipo producto terminado o reventa, y tener la categoría activa.
- **Requisitos del precio:** tiene que tener precio en la lista predeterminada y activa del local.
- **Requisitos del local:** tiene que ser un local, activo y visible en la web.
- **Reventa:** solo la del propio local. Por los triggers de la etapa 2 nunca está en la lista de otro, pero la consulta lo exige igual.
- **Orden:** por nombre.

### El local

- Con `localId`, se usa ese local.
- Sin `localId`, se usa el local central. Si no hay ninguno marcado, responde 400: "Elegí un local: todavía no hay un local central."
- Un local oculto, inactivo, la fábrica o un id que no existe responden todos 404 con el mismo mensaje: "No existe el local indicado."

### El precio

- Sale siempre de la lista predeterminada del local elegido, con `CalculoPrecio.precioFinal`, la misma función que usa el precio del empleado (`GET /api/precios`).
- **Si la entidad fiscal del local discrimina IVA:**
  - Con lista sin IVA, se suma el IVA del artículo al responder: 1000 con 21 % se publica como 1210.00.
  - Con lista con IVA, el precio es el de la lista.
- **Si no lo discrimina:** el precio de la lista es el precio final, sin cambios.

### La disponibilidad

- `disponible` es `true` o `false`: nunca se publica la cantidad.
- Es `true` si la suma de `cantidad_actual` en las ubicaciones activas del local es mayor que cero, como aprobaste.
- El stock de otro local no cuenta, ni el de una ubicación inactiva.
- En la etapa 3, cuando lleguen las reservas de pedidos online, habrá que restarlas.

### Un artículo que no se muestra

- Responde 404 con el mismo cuerpo que uno que no existe: "No existe el artículo indicado."
- Vale para un artículo oculto, inactivo, de categoría inactiva, sin precio en la lista predeterminada o que no se vende en ese local. Así no se revela qué hay.

### Límites

- **Paginación:** el listado se pagina con `pagina`, que empieza en 0, y `tamano`, que por defecto es 20 y como máximo 50.
  - Un tamaño mayor se lleva a 50 y una página negativa, a 0, igual que en `GET /api/stock/movimientos`.
  - La respuesta dice el tamaño que se usó.
- **Búsqueda:**
  - `q` busca en el nombre y la marca, sin distinguir mayúsculas, y se recortan los espacios de los extremos.
  - Tiene un máximo de 100 caracteres: con más, responde 400.
  - `%` y `_` se buscan como texto, no como comodines.
- **Caché:** las cuatro respuestas públicas llevan `Cache-Control: max-age=60, public`. Las de error no llevan caché.

### El local central

- Lo cambia solo el administrador técnico, con `POST /api/admin/ambitos/{id}/central`.
- En una misma transacción desmarca al anterior y marca el nuevo, y audita a los dos:
  - `desmarcar_central`, con el ámbito anterior como afectado y `esCentral` de true a false.
  - `marcar_central`, con el nuevo como afectado y `esCentral` de false a true.
- Si el local ya era el central, no cambia nada ni audita.
- Si el local está oculto o inactivo, o es la fábrica, responde 409: "El local central tiene que ser un local activo y visible en la web."
- Ocultar o desactivar el central responde 409: "Es el local central: para ocultarlo de la web (o desactivarlo), primero marcá otro local como central."
  - El servicio lo valida antes y la base lo garantiza igual.

## 5. Cómo se garantiza que no se filtre nada

- Cada endpoint tiene su DTO propio. No se usan las entidades ni los DTOs internos (`ArticuloResponse`, `ArticuloEmpleadoResponse`).
- Un test por endpoint compara las claves exactas del JSON con una lista permitida, así un campo nuevo hace fallar el test.
- Un test pide todas las respuestas públicas y revisa dos cosas:
  - **Claves:** ninguna clave, en ningún nivel, menciona costo, margen, neto, cantidad, stock, mínimo, máximo, ubicación, lista, historial, lote, movimiento, CUIT, IVA, fiscal, razón social ni condición.
  - **Valores:** en el texto no aparece ninguno de los valores que el test cargó a propósito:
    - Los costos de la fábrica y del local.
    - La cantidad, el mínimo y el máximo del stock.
    - El nombre de las ubicaciones.
    - El precio y el nombre de la lista de la fábrica para locales.
    - Un precio del historial y el nombre de otra lista del local.
    - Los CUIT, las condiciones frente al IVA y las razones sociales.

## 6. Tests nuevos (26)

### `CatalogoPublicoApiTest` (20)

- Las claves exactas de cada endpoint: categorías, catálogo (la página y cada artículo), detalle y locales.
- Ninguna respuesta expone datos internos (ver punto 5).
- Solo aparecen artículos activos, visibles, de categoría activa y con precio en la lista predeterminada. Los demás responden 404 en el detalle.
- Un artículo que no se vende en el local o que está oculto responde exactamente igual que uno que no existe.
- Un local oculto, inactivo, la fábrica o uno inexistente responden 404, en el listado y en el detalle.
- **Precio en un local responsable inscripto con lista sin IVA:** se suma el IVA, 1210.00 con el 21 % y 2210.00 con el 10,5 %.
- **Precio en un local responsable inscripto con lista con IVA:** el de la lista.
- **Precio en un local monotributista:** el de la lista, sin cambios.
- **Disponibilidad:**
  - El artículo figura como disponible si suma más de cero entre las ubicaciones activas, aunque en Principal haya quedado en cero.
  - No figura disponible si solo tiene stock en una ubicación inactiva, o en otro local.
- Sin `localId` se usa el central; sin central, responde 400.
- La paginación tiene un máximo de 50 y la página negativa se lleva a 0.
- La búsqueda funciona por nombre, por marca y por categoría, y los comodines se toman como texto.
- El largo máximo de la búsqueda: 100 caracteres responden 200 y 101, 400.
- Los endpoints no piden login y llevan `Cache-Control: max-age=60, public`.
- POST, PUT y DELETE responden 405.
- Las categorías inactivas no aparecen.

### `LocalCentralTest` (6)

- La base no admite dos locales centrales.
- La base exige que el central sea un local activo y visible: rechaza marcar uno oculto o la fábrica, y ocultar o desactivar el central.
- El administrador marca el central y desmarca al anterior, con las dos auditorías y su ámbito afectado. `GET /api/publico/locales` muestra el cambio.
- Solo el administrador lo cambia: los dueños reciben 403 y sin sesión, 401.
- No se marca como central un local oculto ni la fábrica: responde 409.
- El central no se oculta ni se desactiva hasta marcar otro.

### Cambios en tests existentes

- `AmbitoApiTest.elSitioPublicoMuestraSoloDatosDeContactoSinNadaFiscal` ahora espera también `id` y `esCentral`.

### Resultado

- `./mvnw test`: 229 tests, 0 fallas, 0 errores (203 antes de este módulo).

## 7. Decisiones que no estaban en el plan

- **`id` en `GET /api/publico/locales`:** hacía falta para que el visitante elija el local. Es el id del ámbito, que no revela nada interno.
- **Una categoría inactiva oculta sus artículos** en el sitio público, aunque el artículo esté activo.
- **`GET /api/publico/categorias` muestra todas las categorías activas,** tengan o no artículos publicados.
- **La búsqueda es por nombre y marca,** no por descripción.
- **Un tamaño de página mayor que 50 se lleva a 50,** en lugar de responder 400, como en los movimientos de stock.
- **El caché también se aplica a `GET /api/publico/locales`:** el plan lo pedía para "las respuestas" públicas. Un cambio en el central se ve en menos de un minuto.
- **Marcar como central al que ya lo es no hace nada:** responde 200 sin auditar.
- **El front sigue sin conectar:** `index.html` todavía tiene los productos y precios fijos, como dice el plan (punto 6).

## 8. Pendiente

- V8 todavía no se aplicó a la base de desarrollo: se aplica la próxima vez que se levante la app.
- Hasta que el administrador técnico marque un local central, el catálogo sin `localId` responde 400.
