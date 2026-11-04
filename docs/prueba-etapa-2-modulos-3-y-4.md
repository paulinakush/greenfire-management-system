# Prueba de los módulos 3 y 4 de la etapa 2 contra la base de desarrollo

Fecha: 3 de noviembre de 2026. Rama `etapa-2-catalogo-precios-stock`, con los commits `55a0f01` (stock) y `652d1f8` (conteos físicos).

## 1. Migraciones sobre la base de desarrollo

- **Estado previo:**
  - La base de `docker compose` estaba en la versión 5, con los datos que dejó la prueba de los módulos 1 y 2 (`docs/prueba-etapa-2-modulos-1-y-2.md`).
  - Tenía tres ámbitos: Local Palermo, Local Belgrano y Fábrica (prueba).
  - Tenía seis usuarios y un artículo, "Coco Elite (prueba)", producto terminado de la fábrica.
- **Respaldo:** antes de levantar la app saqué un respaldo completo con `pg_dump -Fc`. No forma parte del repositorio.
- **Arranque:** se levantó con `./mvnw spring-boot:run`, contra la base de desarrollo y sin tocar sus datos.
- **Resultado de Flyway:**
  - El log muestra `Migrating schema "public" to version "6 - stock"`.
  - Después, `Migrating schema "public" to version "7 - conteos"`.
  - Y al final, `Successfully applied 2 migrations to schema "public", now at version v7`.
  - La app arrancó sin errores: `Started GestionApplication in 4.93 seconds`.
- **`flyway_schema_history`:**
  - 1 base, 2 auditoria inmutable, 3 auditoria ambito afectado, 4 catalogo, 5 listas precios, 6 stock y 7 conteos.
  - Todas con `success = true`. La 6 y la 7 quedaron aplicadas el 3 de noviembre a las 18:36.
- **Ubicación "Principal" de cada ámbito existente:**
  - V6 creó una por cada ámbito que ya existía: la 1 para Local Palermo, la 2 para Local Belgrano y la 3 para Fábrica (prueba), todas activas.
  - La consulta de ámbitos sin una ubicación "Principal" devolvió 0.

## 2. Recorrido con curl

- **Cómo se hizo:**
  - Cada usuario usó su propia sesión: cookie `SESSION` más el token CSRF de `GET /api/auth/csrf`, enviado en el header `X-XSRF-TOKEN`.
  - Las contraseñas de los usuarios de prueba de la primera prueba no se habían guardado. El administrador técnico las restableció con `POST /api/admin/usuarios/{id}/password` (204), con valores al azar que no se muestran ni se guardaron.
  - Usuarios: `prueba.palermo@greenfire.local` (dueño de Local Palermo) y `prueba.empleado@greenfire.local` (empleado de Local Palermo).
- **Acceso:**
  - El administrador técnico, `GET /api/stock`: 403.
  - El empleado, `GET /api/conteos/1`: 403.
- **Ajuste (dueño de Local Palermo):**
  - `GET /api/ubicaciones`: 200, con `[{"id":1,"nombre":"Principal","activo":true}]`.
  - Ajuste sin motivo: 400, con `"campos":{"motivo":"no debe estar vacío"}`.
  - Ajuste de -1 sin stock: 409, con `Stock insuficiente: 'Coco Elite (prueba)' en 'Principal' tiene 0 y se quieren sacar 1.`
  - Ajuste de +10 con el motivo "Carga de prueba": 201, con el movimiento 1 y `"cantidadResultante":10.000`.
  - `GET /api/stock`: 200, con cantidad 10 en Principal.
- **Conteo con diferencias (dueño de Local Palermo):**
  - `POST /api/conteos` en Principal: 201, con el conteo 1 abierto y sin artículos.
  - Otro `POST /api/conteos` en la misma ubicación: 409, con `Ya hay un conteo abierto en la ubicación 'Principal'.`
  - `PUT /api/conteos/1/items` con 8 unidades contadas: 200. Mientras está abierto, la respuesta trae `"cantidadSistema":null` y `"diferencia":null`.
  - `POST /api/conteos/1/cerrar`: 200, con estado `cerrado`.

    ```json
    {"articuloId":1,"articuloNombre":"Coco Elite (prueba)","unidadMedida":"unidad","cantidadContada":8.000,"cantidadSistema":10.000,"diferencia":-2.000}
    ```

  - Cargar el conteo cerrado: 409, con `El conteo está cerrado: no se puede modificar.`
  - Anular el conteo cerrado: 409, con el mismo mensaje.
  - `GET /api/stock`: 200, con cantidad 8.
  - `GET /api/stock/movimientos`: 200, con dos movimientos:
    - El 2, de tipo `conteo`, cantidad -2, motivo "Conteo 1" y `"conteoId":1`.
    - El 1, de tipo `ajuste`, cantidad 10, motivo "Carga de prueba" y `"conteoId":null`.
- **Empleado de Local Palermo:**
  - `GET /api/stock`: 200, con la cantidad 8 y sin costos.
  - Revisé con `jq` todas las claves de la respuesta buscando `costo`, `margen` o `precio`: no apareció ninguna.

## 3. Comprobaciones en la base

- **Cache del stock:** `stock_articulos.cantidad_actual` es 8.000 y la suma de los movimientos del artículo en Principal también da 8.000.
- **Auditoría:** quedaron cuatro registros, todos con Local Palermo como ámbito actuante y afectado:
  - `ajustar_stock` del movimiento 1, con el motivo y el renglón.
  - `abrir` del conteo 1, con la ubicación y las observaciones.
  - `cargar` del conteo 1, con `"antes":null` y `"despues":8.000`.
  - `cerrar` del conteo 1, con `"articulosContados":1` y la diferencia: cantidad del sistema 10, contada 8, diferencia -2 y movimiento 2.
- **Restricciones de la base, probadas con `psql` directo:**
  - `update conteos_stock set observaciones = 'x' where id = 1`: rechazado, con `El conteo 1 está cerrado: no se puede modificar.`
  - `update stock_articulos set cantidad_actual = 99`: rechazado, con `La cantidad de stock no se edita: es la suma de los movimientos.`
  - `delete from movimientos_stock where id = 1`: rechazado, con `Los movimientos de stock no se pueden modificar ni borrar (operación DELETE rechazada).`

## 4. Algo para revisar más adelante

- `GET /api/stock/movimientos` devuelve `fechaHora` en UTC (`2026-11-03T21:37:21Z`), mientras que el conteo devuelve `cerradoAt` con la zona de Argentina (`2026-11-03T18:37:21-03:00`).
  - Es el mismo instante, así que no es un error.
  - Conviene unificar el formato cuando se conecte el front. No lo cambié porque no era parte de esta prueba.

## 5. Cómo quedó la base de desarrollo

- **Migraciones:** quedó en la versión 7.
- **Datos de prueba que se agregaron:**
  - Las tres ubicaciones "Principal".
  - Los dos movimientos (ajuste de +10 y conteo de -2) y el stock de 8 unidades de "Coco Elite (prueba)" en Principal de Local Palermo.
  - El conteo 1, cerrado.
- **Datos que cambiaron:** las contraseñas de `prueba.palermo` y `prueba.empleado`.
- **Volver atrás:** se puede volver a la versión 5 con el respaldo.
