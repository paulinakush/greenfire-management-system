# Prueba de los módulos 1 y 2 de la etapa 2 contra la base de desarrollo

Fecha: 3 de noviembre de 2026. Rama `etapa-2-catalogo-precios-stock`, con los commits `e0478b0` (catálogo) y `4a29868` (listas de precios).

## 1. Migraciones sobre la base de desarrollo

- **Estado previo:**
  - La base de `docker compose` estaba en la versión 3, sin V4 ni V5.
  - Tenía dos locales:
    - Local Palermo, de una entidad responsable inscripta.
    - Local Belgrano, de una entidad monotributista.
  - Tenía tres usuarios: el administrador técnico y un dueño de cada local.
  - No había fábrica.
- **Respaldo:** antes de levantar la app saqué un respaldo de la base con `pg_dump`. No forma parte del repositorio.
- **Arranque:** se levantó con `./mvnw spring-boot:run`, contra la base de desarrollo y con los datos que ya tenía.
- **Resultado de Flyway:**
  - El log muestra `Migrating schema "public" to version "4 - catalogo"`.
  - Después, `Migrating schema "public" to version "5 - listas precios"`.
  - Y al final, `Successfully applied 2 migrations to schema "public", now at version v5`.
  - La app arrancó sin errores: `Started GestionApplication in 4.75 seconds`.
- **`flyway_schema_history`:** las versiones 1 a 5 figuran todas con `success = true`.

## 2. Recorrido con curl

- **Cómo se hizo:**
  - Cada usuario usó su propia sesión: cookie `SESSION` más el token CSRF de `GET /api/auth/csrf`, enviado en el header `X-XSRF-TOKEN`.
  - El administrador técnico entró con las credenciales del `.env`.
  - Las contraseñas de los usuarios de prueba se generaron al azar y no se muestran ni se guardaron.
- **Datos que hubo que crear, porque la base no tenía fábrica:**
  - Con el administrador técnico, la entidad fiscal "Green Fire SRL (prueba)", responsable inscripta, y el ámbito "Fábrica (prueba)".
  - Con el administrador técnico, dos dueños de prueba:
    - `prueba.fabrica@greenfire.local`, dueño de la fábrica.
    - `prueba.palermo@greenfire.local`, dueño de Local Palermo. Los dueños que ya existían no se tocaron.
  - Con el dueño de Local Palermo, el empleado `prueba.empleado@greenfire.local`.
- **Dueño de fábrica:**
  - `POST /api/categorias` con "Sustratos (prueba)": 201.
  - `POST /api/articulos` con el producto terminado "Coco Elite (prueba)", IVA del 21 %: 201.
  - `PUT /api/articulos/1/costo` con 1500: 200. La respuesta del dueño de fábrica trae `"costo": 1500.00`.
- **Dueño de Local Palermo:**
  - `POST /api/listas-precios` con "Venta al público (prueba)", con IVA y predeterminada: 201. La respuesta trae `"discriminaIva": true`, porque Local Palermo es responsable inscripto.
  - `PUT /api/listas-precios/1/items/1` con el precio 4200: 200.
    - La respuesta trae `"precioNeto": 3471.07`, que es 4200 / 1,21.
    - Trae `"costo": null` y `"margen": null`, porque el local todavía no tiene costo de los productos de la fábrica (llega con el módulo 6).
  - `GET /api/articulos/1`: 200, con `"costo": null` y `"editable": false`. El costo de 1500 de la fábrica no le llega al local.
- **Empleado de Local Palermo:**
  - `GET /api/precios`: 200.

    ```json
    [{"articuloId":1,"articuloNombre":"Coco Elite (prueba)","precio":4200.00}]
    ```

  - `GET /api/articulos/1`: 200.

    ```json
    {"id":1,"tipo":"producto_terminado","nombre":"Coco Elite (prueba)","descripcion":null,"categoriaId":1,"categoriaNombre":"Sustratos (prueba)","marca":null,"unidadMedida":"unidad","imagen":null,"alicuotaIva":21.00}
    ```

  - `GET /api/articulos`: 200, con el mismo artículo y los mismos campos.
  - `GET /api/listas-precios/1/items`: 403.
- **Comprobación automática:**
  - Revisé con `jq` todas las claves, en cualquier nivel, de las tres respuestas del empleado (`/api/precios`, `/api/articulos/1` y `/api/articulos`), buscando `costo`, `margen` o `neto`.
  - No apareció ninguna.
- **Auditoría:** quedaron registrados `crear` de la categoría, `crear` y `editar_costo` del artículo (con la fábrica como ámbito afectado), y `crear` y `editar_precio` de la lista (con Local Palermo como ámbito afectado).

## 3. Test que comprueba que `ArticuloEmpleadoResponse` no tiene campos de costo

- El test existe: es `ArticuloApiTest.elEmpleadoVeLosArticulosActivosDeSuLocalSinCostos`, en `backend/src/test/java/com/greenfire/catalogo/ArticuloApiTest.java`. No hizo falta escribirlo.
- Qué comprueba:
  - Que cada artículo del listado del empleado tenga exactamente estos campos: `id`, `tipo`, `nombre`, `descripcion`, `categoriaId`, `categoriaNombre`, `marca`, `unidadMedida`, `imagen` y `alicuotaIva`. Si se agrega un campo, el test falla.
  - Que `$.costo` no aparezca al pedir el artículo por id.
  - Que el empleado no vea artículos inactivos ni la reventa de otro local.
- Hay un test equivalente para los precios: `ListaPreciosApiTest.elEmpleadoSoloVeArticuloYPrecioDeVentaDeSuLocal`. Exige que cada precio del empleado tenga solo `articuloId`, `articuloNombre` y `precio`.

## 4. Cómo quedó la base de desarrollo

- **Migraciones:** quedó en la versión 5.
- **Datos de prueba que quedaron:** la entidad "Green Fire SRL (prueba)", el ámbito "Fábrica (prueba)", los tres usuarios `prueba.*@greenfire.local`, la categoría, el artículo, el costo, la lista y el precio.
- **La única fábrica:** como solo puede haber una fábrica y nada se borra, "Fábrica (prueba)" ocupa ese lugar. El administrador técnico puede renombrarla y corregir su entidad fiscal cuando se carguen los datos reales. También se puede volver a la base anterior con el respaldo.
