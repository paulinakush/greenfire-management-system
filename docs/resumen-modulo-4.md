# Resumen del módulo 4 de la etapa 2: conteos físicos

## 1. Qué se hizo

- **Modelo:** `docs/modelo-datos.dbml`, actualizado antes que la migración.
  - `estado_conteo` suma el valor `anulado`.
  - `conteos_stock` suma `cerrado_at` y `anulado_at`.
  - Se documentaron el índice único parcial, la unicidad de `detalle_conteo` y la regla de `movimientos_stock.conteo_id`.
- **Migración:** `V7__conteos.sql`, una migración nueva. V6 no se tocó.
- **Paquete `stock`:**
  - Entidades: `ConteoStock` y `DetalleConteo`.
  - `EstadoConteo`.
  - Repositorios de las dos entidades, sin borrado.
  - `ConteoService` y `ConteoController`.
  - DTOs.
- **Cambios en lo del módulo 3:**
  - `MovimientoStock` tiene la relación con el conteo.
  - `RegistroStock` suma `registrarConteo(...)` y `bloquearCantidades(...)`. `registrar(...)` ya no acepta el tipo `conteo`, para que todo movimiento de conteo apunte a su conteo.
  - `MovimientoResponse` suma `conteoId`.
- **Otros cambios:**
  - `ManejadorErrores` traduce el índice de conteo abierto a 409 "Ya hay un conteo abierto en esa ubicación."
  - `docs/plan-etapa-2.md` suma el apartado 9, "Para revisar en la etapa 3": un dueño de local puede ajustar stock de productos de la fábrica sin entrega ni deuda, y hay que evaluar cómo detectarlo.
- **Tests:** la limpieza de los tests con transacciones reales pasó a una clase común, `DatosConfirmados`, que usan los tests de concurrencia de stock y de conteos.

## 2. La base de datos (V7)

- **`conteos_stock`:**
  - Tiene ubicación, usuario que lo abrió, fecha, estado (`abierto`, `cerrado` o `anulado`), observaciones, `cerrado_at` y `anulado_at`.
  - Un CHECK exige que las fechas coincidan con el estado: abierto sin fechas, cerrado con `cerrado_at` y anulado con `anulado_at`.
- **Un solo conteo abierto por ubicación:** índice único parcial `uq_conteos_stock_abierto` sobre `ubicacion_stock_id`, donde `estado = 'abierto'`.
- **Un conteo cerrado o anulado no se modifica:**
  - Un trigger rechaza todo UPDATE de un conteo que no esté abierto. Así, un cerrado no se puede anular ni reabrir, y un anulado no vuelve a abrirse.
  - Un conteo abierto no cambia de ubicación.
- **Ningún conteo se borra:** el mismo trigger rechaza DELETE y TRUNCATE. Un conteo se anula.
- **`detalle_conteo`:**
  - Tiene artículo, cantidad contada (no negativa) y cantidad del sistema (null hasta el cierre). La unicidad es por conteo y artículo.
  - Un trigger permite insertar, cambiar o quitar renglones solo mientras el conteo está abierto.
  - El artículo tiene que ser uno que el ámbito de la ubicación ve, con la misma regla de V6.
  - Lo que se mide en unidades se cuenta en cantidades enteras.
- **`movimientos_stock.conteo_id`:**
  - Columna nueva, con FK a `conteos_stock`.
  - Un CHECK exige que la tengan los movimientos de tipo `conteo`, y solo ellos.
  - El trigger de validación de movimientos ahora exige además que el movimiento sea de la ubicación de su conteo.

## 3. Endpoints

- **Abrir:** `POST /api/conteos`, con `ubicacionId` y `observaciones`. Responde 201.
- **Consultar:**
  - `GET /api/conteos`, con filtros `ubicacionId` y `estado`. Del más nuevo al más viejo.
  - `GET /api/conteos/{id}`, con el detalle.
- **Cargar cantidades:** `PUT /api/conteos/{id}/items`, con `items: [{articuloId, cantidadContada}]`.
- **Cerrar:** `POST /api/conteos/{id}/cerrar`.
- **Anular:** `POST /api/conteos/{id}/anular`, solo si está abierto.

## 4. Permisos

- **Quién participa:** abren, cargan, cierran, anulan y consultan el dueño de fábrica (en la fábrica) y el dueño de local (en su local).
- **Empleado de local:** recibe 403 en todos los endpoints, también en la consulta.
- **Administrador técnico:** recibe 403.
- **Sin sesión:** 401.
- **Otros ámbitos:**
  - Un conteo de otro ámbito responde 404 al consultar, cargar, cerrar o anular.
  - Abrir un conteo en una ubicación de otro ámbito también responde 404.
- **Artículos:** solo se cuentan artículos que el ámbito ve. Un insumo en un local o la reventa de otro local responden 404.

## 5. Cómo funciona

### Carga

- Se puede cargar de a partes: cada carga agrega o reemplaza los artículos que trae y deja los demás como estaban.
- Una cantidad null saca el artículo del conteo.
- Mientras el conteo está abierto, la respuesta no muestra la cantidad del sistema ni la diferencia, para contar sin verlas.

### Cierre

- **Pasos, todos en una sola transacción:**
  1. Bloquea el conteo (`SELECT ... FOR UPDATE`) y exige que siga abierto.
  2. `RegistroStock.bloquearCantidades` bloquea las filas de stock de los artículos contados en el mismo orden que `RegistroStock` (artículo y después ubicación) y devuelve la cantidad del sistema en ese momento. Si un artículo todavía no tiene fila de stock, la crea con cantidad cero para poder bloquearla.
  3. Cada diferencia distinta de cero (contada − sistema) se registra con `RegistroStock.registrarConteo`, como movimiento de tipo `conteo` con el `conteo_id` y el motivo "Conteo N".
  4. Guarda la cantidad del sistema en cada renglón, pasa el conteo a cerrado y lo audita.
- **Todo o nada:** si cualquier paso falla, incluido el registro de auditoría, se revierte todo. El conteo sigue abierto, sin cantidades del sistema y sin movimientos.
- **Carga inicial:** es un conteo contra cero. Los artículos sin fila de stock cuentan contra 0, y lo contado entra como movimientos de tipo `conteo`.
- **Artículos no contados:** quedan como estaban. Un conteo puede ser parcial.
- **Simultaneidad:**
  - Si hay un ajuste en curso sobre un artículo contado, el cierre espera a que termine y toma la cantidad que quedó después del ajuste.
  - Dos operaciones sobre el mismo conteo, como cerrar y anular, se ordenan por el bloqueo del conteo.

### Anulación

- Solo se anula un conteo abierto. Pasa a `anulado`, no genera movimientos y libera la ubicación para abrir otro.

### Auditoría

- Todas las acciones usan la entidad `conteo_stock`, con el ámbito como afectado.
- **`abrir`:** con la ubicación y las observaciones.
- **`cargar`:** con cada artículo cuya cantidad cambió, con el valor anterior y el nuevo. Si la carga no cambia nada, no se audita.
- **`cerrar`:** con la cantidad de artículos contados y las diferencias. Cada diferencia lleva artículo, cantidad del sistema, cantidad contada, diferencia y el id del movimiento generado.
- **`anular`:** con el estado anterior y el nuevo.

## 6. Tests nuevos (22)

### `ConteoMigracionTest` (lo que garantiza la base, 7)

- `hayUnSoloConteoAbiertoPorUbicacion`: el índice parcial rechaza un segundo conteo abierto, permite uno en otra ubicación y otro en la misma después de cerrar el primero.
- `unConteoCerradoNoSeModificaNiSeAnula`: la base rechaza cambiar o anular un conteo cerrado y tocar su detalle.
- `unConteoAnuladoNoVuelveAAbrirse`: la base rechaza reabrirlo o cargarle renglones.
- `ningunConteoSeBorra`: la base rechaza DELETE y TRUNCATE.
- `elEstadoYSusFechasSonCoherentes`: no hay cerrado sin `cerrado_at`, abierto con fecha de cierre ni estados inventados.
- `elDetalleSoloTieneArticulosQueElAmbitoVeContadosEnCantidadesValidas`: se rechazan la reventa de otro local, decimales en unidades, cantidades negativas y artículos repetidos.
- `losMovimientosDeConteoApuntanAUnConteoDeSuUbicacion`: un movimiento de conteo exige `conteo_id` de un conteo de su misma ubicación, y otro tipo de movimiento no puede tenerlo.

### `ConteoApiTest` (API y acceso, 12)

- `unCierreConDiferenciasLasRegistraComoMovimientosYLasAudita`:
  - 10 → 8 y 5 → 7: dos movimientos de conteo con su `conteoId` y el stock igual a lo contado.
  - Cantidades del sistema y diferencias en la respuesta.
  - Auditoría de apertura, carga y cierre con el ámbito afectado, y el cierre con sus diferencias.
- `unCierreSinDiferenciasNoGeneraMovimientos`: el conteo se cierra sin movimientos y con la lista de diferencias vacía.
- `laCargaInicialEsUnConteoContraCeroDeArticulosSinStock`: artículos sin fila de stock cuentan contra 0, y el stock inicial entra solo con movimientos de tipo `conteo`.
- `unConteoSinArticulosNoSeCierra`: responde 409.
- `mientrasEstaAbiertoSeCargaDeAPartesSinVerLaCantidadDelSistema`: carga de a partes, quita un artículo con null, no muestra la cantidad del sistema y no audita una carga sin cambios.
- `soloSeCuentanArticulosQueElAmbitoVeEnCantidadesValidas`: un insumo o la reventa de otro local responden 404, decimales en unidades 409 y cantidades negativas 400.
- `noPuedeHaberDosConteosAbiertosEnLaMismaUbicacion`: el segundo responde 409; se permite en otra ubicación, y en la misma después de anular el primero.
- `unConteoCerradoNoSeModificaNiSeAnula`: cargar, cerrar o anular un conteo cerrado responde 409.
- `soloSeAnulaUnConteoAbiertoYNoGeneraMovimientos`: la anulación no mueve el stock, y después cargar, cerrar o anular responde 409.
- `noSeAbreUnConteoEnUnaUbicacionDesactivada`: responde 409.
- `elEmpleadoYElAdministradorTecnicoNoParticipan`: reciben 403 en todo, incluida la consulta; sin sesión, 401.
- `nadieVeNiTocaLosConteosDeOtroAmbito`: otro local y la fábrica reciben 404 al consultar, cargar, cerrar o anular; también responde 404 abrir en una ubicación ajena, y el listado y sus filtros no muestran conteos ajenos.

### `ConteoConcurrenciaTest` (transacciones reales, 3)

- `unCierreQueFallaAMitadDeCaminoNoAplicaNada`:
  - Hace fallar el último paso del cierre, el insert de auditoría, cuando ya se registraron los movimientos, se tomó la cantidad del sistema y el conteo pasó a cerrado.
  - Responde 500, y en la base el conteo sigue abierto, sin cantidades del sistema, sin movimientos, con el stock intacto y sin la fila de stock nueva.
  - Sin la falla, el mismo conteo se cierra bien.
- `unCierreSimultaneoConUnAjusteTomaLaCantidadDespuesDelAjuste`:
  - Un ajuste de −2 mantiene el bloqueo, y el cierre por la API queda esperando (el test verifica que no termina en 500 ms).
  - Cuando el ajuste confirma, el cierre toma 8 como cantidad del sistema, registra una diferencia de −1 y el stock queda en lo contado, 7.
- `dosAperturasSimultaneasEnLaMismaUbicacionDejanUnSoloConteoAbierto`: dos dueños abren a la vez; uno recibe 201 y el otro 409, y queda un solo conteo abierto.
- Los tests de concurrencia de stock y de conteos se corrieron cinco veces seguidas y pasaron todas.

### Resultado

- Pasan los 203 tests del backend: los 181 anteriores y los 22 nuevos, sin fallas.

## 7. Decisiones que no estaban en el plan

- **Estado `anulado`:** el modelo tenía solo `abierto` y `cerrado`. Lo agregué, con `cerrado_at` y `anulado_at`, para que anular sea un cambio de estado y no un borrado.
- **Conteos parciales:** los artículos no contados quedan como estaban. Un conteo no lleva a cero lo que no se contó.
- **Carga de a partes:** cada `PUT` agrega o reemplaza, y `cantidadContada: null` saca un artículo. El plan no definía si el `PUT` reemplazaba todo.
- **Conteo a ciegas:** mientras está abierto, la respuesta no muestra la cantidad del sistema ni la diferencia.
- **Filas nuevas al cerrar:** se crean con cantidad cero las filas de stock que falten, para poder bloquearlas. Así un primer movimiento simultáneo no se puede colar.
- **Conteos vacíos:** un conteo sin artículos no se puede cerrar (409); se puede anular.
- **Ubicaciones desactivadas:** no se abre un conteo en una ubicación desactivada (409).
- **Consulta solo para dueños:** el plan decía que el empleado no participa; lo apliqué también a la consulta.
- **`RegistroStock.registrar` sin conteos:** ya no acepta el tipo `conteo`, para que todo movimiento de conteo pase por `registrarConteo` y lleve su conteo.
- **Motivo de los movimientos:** "Conteo N", con el número del conteo.
- **Nombres de auditoría:** `abrir`, `cargar`, `cerrar` y `anular`, con la entidad `conteo_stock`.
- **Sin prueba en desarrollo:** no apliqué V6 ni V7 sobre la base de desarrollo. Las migraciones se probaron con los tests, que crean una base nueva.
