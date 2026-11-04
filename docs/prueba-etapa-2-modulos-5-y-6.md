# Prueba de los módulos 5 y 6 de la etapa 2 contra la base de desarrollo

Fecha: 4 de noviembre de 2026. Rama `etapa-2-catalogo-precios-stock`, con los commits `490bae5` (catálogo público) y `5cc677b` (entregas).

## 1. Migraciones sobre la base de desarrollo

- **Respaldos:** antes de cada migración saqué un respaldo completo con `pg_dump -Fc`, uno antes de V8 y otro antes de V9. No forman parte del repositorio.
- **V8 (local central):**
  - Se aplicó primero, a las 17:37, con la base en la versión 7.
  - El log muestra `Migrating schema "public" to version "8 - local central"` y `Successfully applied 1 migration ... now at version v8`, sin errores.
  - Quedaron creadas la restricción `ambitos_central_valido` y el índice `uq_ambitos_central`.
  - Ningún ámbito quedó marcado como central.
- **V9 (entregas):**
  - Se aplicó a las 17:57, con la base en la versión 8.
  - El log muestra `Migrating schema "public" to version "9 - entregas"` y `Successfully applied 1 migration ... now at version v9`.
  - La app arrancó sin errores: `Started GestionApplication in 3.617 seconds`.
- **`flyway_schema_history`:** registra 1 base, 2 auditoria inmutable, 3 auditoria ambito afectado, 4 catalogo, 5 listas precios, 6 stock, 7 conteos, 8 local central y 9 entregas, todas con `success = true`.

## 2. Catálogo público (sin login)

- **`GET /api/publico/locales`:** 200, con `Cache-Control: max-age=60, public`.
  - Devuelve Local Belgrano y Local Palermo, cada uno con `id` y `"esCentral":false`.
  - No trae ningún dato fiscal.
- **`GET /api/publico/catalogo?localId=1`:** 200, con `Cache-Control: max-age=60, public`.
  - Devuelve "Coco Elite (prueba)" a 4200.00 y `"disponible":true`.
  - El precio es el de la lista de Local Palermo, que es con IVA, así que se publica tal cual.
  - No aparecen ni el costo de la fábrica (1500) ni el precio de la lista de la fábrica para locales (3000), que se cargó durante la prueba de entregas.
- **`GET /api/publico/catalogo/1?localId=1`:** 200, con los mismos diez campos del listado.
- **`GET /api/publico/catalogo?localId=3`:** 3 es el id de la fábrica. Responde 404 con "No existe el local indicado.", y la respuesta de error no lleva caché.
- **`GET /api/publico/catalogo`, sin local:** 400 con "Elegí un local: todavía no hay un local central.".

## 3. Recorrido de una entrega con curl

- **Sesiones:**
  - Cada usuario usó su propia sesión: cookie `SESSION` más el token CSRF de `GET /api/auth/csrf`, enviado en el header `X-XSRF-TOKEN`.
  - El administrador técnico restableció las contraseñas de `prueba.fabrica`, `prueba.palermo` y `prueba.empleado` (204), con valores al azar que no se muestran ni se guardaron.
- **Preparación (dueño de fábrica):**
  - Ajuste de +20 de "Coco Elite (prueba)" en Principal de la fábrica, con el motivo "Carga de prueba": 201.
  - Lista "Para locales (prueba)", sin IVA y predeterminada: 201, con `"discriminaIva":true`.
  - Precio de 3000 para el artículo en esa lista: 200.
- **Envío (dueño de fábrica):**
  - `POST /api/entregas` de 10 unidades a Local Palermo: 201.
    - La entrega 1 queda `enviada`, con `precioUnitario` 3000.00, `subtotal` 30000.00 y `total` 30000.00.
    - La respuesta trae la ubicación de origen y la lista.
  - Otro envío de 999 unidades: 409, con "Stock insuficiente: 'Coco Elite (prueba)' en 'Principal' tiene 10 y se quieren sacar 999.".
  - `GET /api/stock`: 10 en Principal de la fábrica.
- **Antes de recibir:**
  - **El empleado de Local Palermo,** con `GET /api/entregas/1`: 200, sin `precioUnitario`, `subtotal` ni `total`. Revisé con `jq` todas las claves de la respuesta y no apareció ninguna con precio, subtotal ni total.
  - **El dueño de Local Palermo,** con `GET /api/entregas/1`: 200, con los precios y el total, y sin la ubicación de origen ni la lista de la fábrica.
- **Recepción con faltante (empleado de Local Palermo):**
  - Recibir 11 responde 409, con "De 'Coco Elite (prueba)' se enviaron 10: no se pueden recibir 11.".
  - Recibir 8 en Principal, con la observación "Llegaron 2 bolsas rotas", responde 200 y la entrega queda en `diferencia_informada`.
  - Que el dueño de Local Palermo reciba otra vez responde 409, con "La entrega está diferencia_informada: ya no se puede recibir ni anular.".
  - Que la fábrica anule la entrega recibida responde 409, con el mismo mensaje.
- **Resolución (dueño de fábrica):**
  - `GET /api/entregas/1`: 200, con lo enviado y lo recibido. No lleva la ubicación de destino del local.
  - Resolver sin motivo responde 400, con `"campos":{"motivo":"no debe estar vacío"}`.
  - Resolver por `reingreso`, con el motivo "Las 2 bolsas volvieron en el camión", responde 200.
    - La respuesta trae `"resolucionDiferencia":"reingreso"` y la fecha de resolución.
    - El estado sigue en `diferencia_informada`.
  - Resolver otra vez responde 409, con "La diferencia de la entrega ya está resuelta.".
- **Cómo quedó el stock:**
  - **Fábrica:** 12 en Principal.
    - La cuenta es 20 − 10 + 2.
    - `GET /api/stock/movimientos` muestra el ajuste de +20, la `entrega_salida` de −10 y la `devolucion` de +2, estas dos con `"entregaId":1`.
  - **Local Palermo:** 16 en Principal, que son los 8 que tenía de la prueba anterior más los 8 recibidos.
  - **Costo:** el dueño de Local Palermo ve `"costo":3000.00` en `GET /api/articulos/1`, el precio de la entrega.
  - **Cuenta total:** enviado 10 = recibido 8 + reingresado 2.
- **Administrador técnico:** `GET /api/entregas` responde 403.

## 4. Comprobaciones en la base

- **`entregas`:** la entrega 1 está en `diferencia_informada`, con `total` 30000.00, `resolucion_diferencia` `reingreso` y fecha de resolución.
- **`detalle_entregas`:** enviada 10.000, recibida 8.000, precio 3000.00 y subtotal 30000.00.
- **Auditoría:** seis registros, dos por paso (uno por ámbito afectado).
  - **`enviar`:**
    - El registro de la fábrica lleva la ubicación de origen, la lista y el movimiento.
    - El del local lleva solo el local, el total y los renglones.
  - **`recibir`:** la acción la hizo Local Palermo (ámbito 1).
    - El registro de la fábrica lleva el estado, lo enviado, lo recibido y las observaciones.
    - El del local lleva además la ubicación de destino, el movimiento y el cambio de costo, de null a 3000.00.
  - **`resolver_diferencia`:**
    - El registro de la fábrica lleva la forma, el motivo, el faltante y el movimiento de devolución.
    - El del local lleva lo mismo sin el movimiento.
- **Caché del stock:** no quedó ninguna fila de `stock_articulos` cuya cantidad difiera de la suma de sus movimientos.
- **Restricción, con `psql` directo:** `update entregas set total = 1 where id = 1` es rechazado, con "Los datos del envío de la entrega 1 no se modifican.".

## 5. Algo para revisar más adelante

- **Formato de las fechas en las entregas:** la misma diferencia que ya se vio en los movimientos.
  - La respuesta de una operación (envío, recepción o resolución) devuelve la fecha con la zona de Argentina (`-03:00`).
  - Las consultas la devuelven en UTC (`Z`).
  - Es el mismo instante, pero conviene unificarlo cuando se conecte el front.

## 6. Cómo quedó la base de desarrollo

- **Migraciones:** quedó en la versión 9.
- **Datos de prueba que se agregaron:**
  - La lista "Para locales (prueba)" de la fábrica, con un precio.
  - El ajuste de +20 en la fábrica.
  - La entrega 1, con sus movimientos y su resolución.
  - El costo de 3000 de "Coco Elite (prueba)" en Local Palermo.
- **Datos que cambiaron:** las contraseñas de los tres usuarios `prueba.*`.
- **Volver atrás:** se puede volver a la versión 8 con el respaldo de antes de V9, o a la 7 con el de antes de V8.
