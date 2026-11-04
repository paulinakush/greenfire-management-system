# Resumen del módulo 6 de la etapa 2: entregas de la fábrica a los locales

## 1. Qué se hizo

- **Uso:** las entregas se construyen en esta etapa pero no se usan en producción hasta la etapa 3, cuando generen la cuenta corriente. Quedó anotado en `CLAUDE.md` ("Decisiones de entregas").
- **Modelo:** `docs/modelo-datos.dbml`, actualizado antes que la migración.
  - `estado_entrega` suma `anulada`.
  - Enum nuevo `resolucion_diferencia`, con los valores `reingreso` y `perdida`.
  - `entregas` suma `usuario_anulacion_id`, `anulada_at`, `resolucion_diferencia`, `motivo_resolucion` y `usuario_resolucion_id`.
  - Se documentaron las reglas de `detalle_entregas` y de `movimientos_stock.entrega_id`.
  - La nota de `costos_articulo` ahora dice que el costo en el local es el precio de la entrega, sin ajustes de IVA.
- **Migración:** `V9__entregas.sql`, una migración nueva. V1 a V8 no se tocaron.
- **Paquete nuevo `entregas`:**
  - Entidades: `Entrega` y `DetalleEntrega`, las dos con `@DynamicUpdate`.
  - Enums: `EstadoEntrega` y `ResolucionDiferencia`.
  - Repositorios sin borrado.
  - `EntregaService` y `EntregaController`.
  - DTOs de pedido: `EnviarEntregaRequest`, `RecepcionRequest` y `ResolverDiferenciaRequest`.
  - DTOs de respuesta, uno por perfil: `EntregaResponse` para la fábrica, `EntregaLocalResponse` para el dueño del local y `RecepcionEmpleadoResponse` para el empleado. Los tres implementan `EntregaVista`.
- **Paquete `stock`:**
  - `RegistroStock.registrarEntrega` registra los movimientos `entrega_salida`, `entrega_recepcion`, `anulacion` y `devolucion` con su `entrega_id`, en el mismo orden de bloqueo que los demás.
  - `RegistroStock.registrar` ya no acepta esos tipos.
  - `MovimientoStock` y `MovimientoResponse` suman `entregaId`.
  - `UbicacionService.ambitoDe` y `buscarActiva` pasan a ser públicos, para usarlos desde `entregas`.
- **Errores:** `ManejadorErrores` traduce `detalle_entregas_recibida_no_mayor` a 409.
- **Documentación:**
  - `docs/plan-etapa-2.md` suma, en "Para revisar en la etapa 3", la nota sobre el IVA en el costo.
  - `CLAUDE.md` suma el paquete `entregas`.

## 2. La base de datos (V9)

### `entregas`

- **Estado y datos coherentes:** el CHECK `entregas_estado_coherente` exige que cada estado tenga sus datos, y solo esos.
  - `enviada` no tiene recepción ni anulación.
  - `recibida` y `diferencia_informada` tienen ubicación de destino, usuario y fecha de recepción.
  - `anulada` tiene usuario y fecha de anulación, y no tiene recepción.
- **Resolución coherente:** el CHECK `entregas_resolucion_coherente` permite la resolución solo con `diferencia_informada`, y exige forma, motivo no vacío, usuario y fecha.
- **Trigger `entregas_validar`:**
  - **Al crear:**
    - La entrega nace `enviada`.
    - Sale de la fábrica y va a un local.
    - La ubicación de origen y la lista de precios son de la fábrica.
  - **Datos fijos:** origen, destino, ubicación de origen, lista, usuario y fecha de envío, total y observaciones no cambian nunca.
  - **Transiciones válidas:**
    - De `enviada` a `recibida`, a `diferencia_informada` (con una ubicación de destino del local) o a `anulada`.
    - De `diferencia_informada` sin resolver a resuelta, una sola vez y sin tocar la recepción.
    - Cualquier otro cambio se rechaza.
  - **Borrado:** DELETE y TRUNCATE se rechazan, porque las entregas se anulan.

### `detalle_entregas`

- **`detalle_entregas_recibida_no_mayor`:** un CHECK que impide que la cantidad recibida supere la enviada.
- **Cantidades:** la enviada es mayor que cero y la recibida, mayor o igual que cero.
- **`detalle_entregas_subtotal`:** un CHECK que exige `subtotal = round(cantidad_enviada × precio_unitario, 2)`.
- **Un renglón por artículo y lote:** lo garantiza el índice único `uq_detalle_entregas_renglon`. El lote es null hasta la etapa 4.
- **Trigger `detalle_entregas_validar`:**
  - Solo admite productos terminados de la fábrica, en cantidades enteras si se miden en unidades.
  - Los renglones se cargan con la entrega enviada.
  - Después solo se carga la cantidad recibida, una vez y con la entrega todavía enviada.
  - Nada se borra.

### Verificación diferida del total

- `entregas_total` y `detalle_entregas_total` son constraint triggers diferidos.
- Al confirmar la transacción, verifican que cada entrega tenga al menos un renglón y que su total sea la suma de los subtotales.

### `movimientos_stock.entrega_id`

- Tiene FK a `entregas`.
- **`movimientos_stock_entrega_segun_tipo`:** un CHECK por el que `entrega_id` es obligatorio si y solo si el tipo es `entrega_salida`, `entrega_recepcion`, `anulacion` o `devolucion`.
- **`movimientos_stock_validar`:** se reemplaza para sumar tres reglas.
  - La salida, la anulación y la devolución son de la ubicación de origen, y la recepción de la de destino.
  - La salida resta y los demás suman.
  - El artículo tiene que ser uno de los renglones de la entrega.

## 3. Endpoints

- **`POST /api/entregas`:** envía. Lleva `localId`, `ubicacionOrigenId`, `listaPreciosId`, `observaciones` y `renglones`, cada uno con `articuloId`, `cantidad` y `loteId` opcional. Responde 201.
- **`GET /api/entregas?estado=`** y **`GET /api/entregas/{id}`:** consultan, con la vista que corresponde al perfil.
- **`POST /api/entregas/{id}/anular`:** anula.
- **`POST /api/entregas/{id}/recepcion`:** recibe. Lleva `ubicacionId`, `observaciones` y `renglones`, cada uno con `renglonId` y `cantidadRecibida`.
- **`POST /api/entregas/{id}/resolver-diferencia`:** resuelve la diferencia. Lleva `forma` (`reingreso` o `perdida`) y `motivo`.

## 4. Permisos y privacidad

- **Dueño de fábrica:** envía, anula y resuelve diferencias. Ve solo las entregas que envió.
- **Dueño de local y empleado del local destino:** registran la recepción. Ven solo las entregas que reciben.
- **Otro ámbito:** recibe 404, tanto al consultar como al operar.
- **Administrador técnico:** recibe 403 en todo, incluida la consulta.
- **Qué ve cada uno:**
  - **La fábrica:** `EntregaResponse`, con precios, su ubicación de origen y su lista. No lleva la ubicación de destino, ni nada del stock o la caja del local.
  - **El dueño del local:** `EntregaLocalResponse`, con precios (son su costo) y su ubicación de destino. No lleva la ubicación de origen ni la lista de la fábrica.
  - **El empleado:** `RecepcionEmpleadoResponse`, sin precios, subtotales ni total.

## 5. Cómo funciona

### Envío

- **Validaciones:** el local, la ubicación de origen y la lista tienen que ser válidos.
  - Si no existen o son de otro lado, responde 404.
  - Si el local o la lista están desactivados, responde 409.
- **Cada renglón:**
  - Tiene que ser un producto terminado activo de la fábrica, con precio en la lista; si no, responde 409. Un artículo repetido (mismo artículo y lote) también responde 409.
  - Un artículo que la fábrica no ve, como la reventa de un local, responde 404.
- **Importes:** el precio de la lista queda guardado en el renglón, con `subtotal = cantidad × precio` redondeado a dos decimales. El total es la suma de los subtotales.
- **Stock:** sale del origen con `RegistroStock.registrarEntrega` (`entrega_salida`), con el mismo orden de bloqueo que los demás movimientos.
  - Si un renglón no alcanza, responde 409 "Stock insuficiente" y no queda nada: ni la entrega, ni los renglones, ni los movimientos.

### Recepción

- **Bloqueo:** se bloquea la entrega (`SELECT ... FOR UPDATE`) y se exige que siga `enviada`. Si no, responde 409.
- **Ubicación de destino:** tiene que ser una ubicación activa del local; una de otro ámbito responde 404.
- **Renglones:**
  - Se carga la cantidad recibida de cada renglón de la entrega, exactamente una vez. Si falta uno, sobra uno o se repite, responde 409.
  - Si la cantidad recibida es mayor que la enviada, responde 409.
- **Resultado:**
  - Entra el stock recibido (`entrega_recepcion`) en la ubicación de destino.
  - Si falta algo, la entrega queda en `diferencia_informada`, con las observaciones del local. Si no falta nada, queda `recibida`.
- **Una sola vez:** dos recepciones simultáneas se ordenan por el bloqueo; la segunda ve la entrega ya recibida y responde 409.
- **Costo:** el costo del producto en el local pasa a ser el precio unitario de la entrega, tal cual, sin ajustes de IVA.
  - Solo cambia si llegó algo de ese renglón.
  - Hay un comentario en `EntregaService.actualizarCostos` y la nota en "Para revisar en la etapa 3": el tratamiento del IVA depende de la condición fiscal de la fábrica y del local, y lo tiene que confirmar el contador.

### Anulación

- Solo se anula una entrega `enviada`, antes de la recepción, con la entrega bloqueada.
- Devuelve todo lo enviado a la ubicación de origen (`anulacion`).
- Una anulación simultánea con una recepción se ordena por el bloqueo: gana una y la otra responde 409.

### Resolución de la diferencia

- Solo se resuelve una entrega en `diferencia_informada` sin resolver. Si ya está resuelta, o no tiene diferencia, responde 409.
- El motivo es obligatorio; sin él responde 400.
- **Formas de resolver:**
  - `reingreso`: el faltante vuelve a la ubicación de origen de la fábrica (`devolucion`).
  - `perdida`: el faltante se da por perdido y no genera movimiento.
- Queda registrada la fecha de resolución (`diferencia_resuelta_at`), con la forma, el motivo y el usuario.
- El estado sigue siendo `diferencia_informada`; lo que marca que está resuelta es esa fecha.

### Auditoría

- Cada paso (`enviar`, `recibir`, `anular` y `resolver_diferencia`) se audita con la fábrica y con el local como ámbitos afectados, en dos registros.
- Quien actúa ve los dos registros: el suyo y el del otro ámbito, porque figura como ámbito actuante. El otro ámbito ve solo el suyo.
- Por eso los datos internos de quien actúa van solo en su propio registro:
  - **Al enviar:** la ubicación de origen, la lista y los movimientos de la fábrica.
  - **Al recibir:** la ubicación de destino, los movimientos y los cambios de costo del local.
- El registro del otro ámbito lleva solo lo que los dos pueden ver: los renglones, los precios, las cantidades y el estado.

## 6. Tests nuevos (41)

### `EntregaMigracionTest` (lo que garantiza la base, 14)

- `enviadaSoloPasaARecibidaDiferenciaInformadaOAnulada`: rechaza cualquier cambio sobre una entrega recibida, anulada o con diferencia, y un cambio sobre una enviada que no sea una transición.
- `losDatosDelEnvioNoCambian`: el total, el destino y la lista no se modifican.
- `cadaEstadoLlevaSusDatos`: rechaza un estado sin sus datos, con datos de otro estado o con un valor que no existe.
- `unaDiferenciaSeResuelveUnaVezConMotivo`: no se resuelve una entrega recibida, ni con un motivo vacío o una forma inválida, y la resolución no se repite ni se edita.
- `ningunaEntregaNiRenglonSeBorra`: rechaza DELETE y TRUNCATE de las dos tablas.
- `vaDeLaFabricaAUnLocalConUbicacionesYListaDeCadaLado`:
  - Rechaza un origen que no sea la fábrica, un destino que no sea un local, y una ubicación de origen o una lista de otro ámbito.
  - Rechaza también una entrega que nace recibida y una ubicación de destino de otro local.
- `unRenglonRecibidoNuncaEsMayorQueElEnviado`: rechaza recibir más de lo enviado, una cantidad negativa o fraccionaria en unidades, y una segunda carga.
- `elDetalleSoloLlevaProductosTerminadosDeLaFabricaConSubtotalCorrecto`: rechaza un insumo, la reventa de un local, un renglón repetido, un subtotal mal calculado, cantidades fraccionarias o en cero, y un cambio de precio.
- `alConfirmarUnaEntregaTieneQueTenerRenglones`, `alConfirmarElTotalTieneQueSerLaSumaDeLosRenglones`, `unaEntregaConRenglonesYSuTotalPasaLaVerificacion` y `unRenglonAgregadoDespuesDescuadraElTotal`: la verificación diferida del total, adelantada con `SET CONSTRAINTS ALL IMMEDIATE`.
- `losMovimientosDeUnaEntregaApuntanAEllaEnLaUbicacionQueCorresponde`: cubre la FK obligatoria según el tipo, la ubicación de origen o de destino según el tipo, y el signo de cada tipo.
- `elArticuloDelMovimientoTieneQueSerDeLaEntrega`.

### `EntregaApiTest` (API, acceso y circuito, 22)

- **Envío:**
  - `elDuenoDeFabricaEnviaConElPrecioDeLaListaYSaleElStock`: verifica el precio, el subtotal y el total; los movimientos `entrega_salida`; y los dos registros de auditoría, sin datos de la fábrica en el del local.
  - `soloSeEntreganProductosTerminadosDeLaFabricaConPrecioEnLaLista`: un insumo, un artículo sin precio, uno inactivo o uno repetido responden 409; la reventa de un local, 404.
  - `laListaYLaUbicacionDeOrigenSonDeLaFabricaYElDestinoUnLocal`.
- **Permisos:**
  - `soloElDuenoDeFabricaEnviaAnulaYResuelve`.
  - `elAdministradorTecnicoNoVeEntregas`.
  - `otroLocalNoVeNiRecibeLaEntrega`: el otro local recibe 404 y su listado sale vacío.
- **Privacidad:**
  - `elEmpleadoRecibeConUnaVistaSinPreciosNiTotales`: compara las claves exactas, antes y después de recibir y en el listado.
  - `elDuenoDelLocalVeLosPreciosDeLoQueRecibePeroNadaDelStockDeLaFabrica`: compara las claves exactas.
  - `laFabricaVeLasQueEnvioSinNadaDelStockDelLocal`: compara las claves exactas y verifica el registro de auditoría de la recepción que ve la fábrica.
- **Recepción:**
  - `laRecepcionEntraElStockYElCostoPasaASerElPrecioDeLaEntrega`: verifica también que una segunda entrega con otro precio actualiza el costo.
  - `noSeRecibeMasDeLoEnviado`.
  - `laUbicacionDeDestinoTieneQueSerDelLocal`.
  - `conFaltanteQuedaConDiferenciaInformada`.
  - `laRecepcionTieneQueTraerCadaRenglonUnaVez`.
  - `soloSeRecibeUnaVez`.
- **Anulación:**
  - `anularDevuelveElStockAlOrigenYDespuesNoSeRecibe`.
  - `noSeAnulaUnaEntregaRecibida`.
- **Resolución:**
  - `reingresarElFaltanteLoDevuelveALaUbicacionDeOrigen`.
  - `darPorPerdidoElFaltanteNoMueveStock`.
  - `laResolucionExigeMotivoYNoSeRepite`.
  - `sinDiferenciaNoHayNadaQueResolver`.
- **Consistencia:** `elStockEntreOrigenDestinoYLoPerdidoCoincideConLoEnviado`.
  - Envía 10, recibe 7 y da por perdido 3: lo que hay en destino más lo perdido es igual a lo enviado, y lo que hay en origen, en destino y lo perdido es igual al stock inicial.
  - Repite el cálculo con una resolución por reingreso.
  - Verifica que la cantidad guardada de cada fila de stock sea la suma de sus movimientos.

### `EntregaConcurrenciaTest` (transacciones reales, 5)

- `unaEntregaQueFallaEnUnRenglonNoMueveNada`:
  - Con un renglón sin stock suficiente responde 409, y no quedan entregas, renglones, movimientos ni auditoría.
  - Con stock suficiente, la misma entrega se confirma y pasa la verificación diferida del total.
- `dosRecepcionesSimultaneasUnaGanaYLaOtraRecibe409`: entra una sola recepción.
- `laSegundaRecepcionEsperaElBloqueoDeLaEntregaYDespuesRecibe409`:
  - Una transacción tiene bloqueada la entrega y la recepción espera.
  - Al soltar, la recepción se registra, y una tercera responde 409.
- `anularALaVezQueSeRecibeDejaUnSoloResultadoConsistente`: gana una de las dos. Si ganó la anulación, el stock volvió al origen; si ganó la recepción, está en el destino.
- `dosEnviosQueCompitenPorElMismoStockNoLoDejanNegativo`: con 5 en stock y dos envíos de 4, uno responde 201, el otro 409, y quedan 1 en stock.

Los tests de concurrencia de stock, conteos y entregas se corrieron cinco veces seguidas y pasaron todas.

### Cambios en tests existentes

- **`StockMigracionTest.todoAjusteExigeMotivoYNingunMovimientoEsCero`:** usaba un movimiento `entrega_recepcion` sin entrega para mostrar que los demás tipos no exigen motivo. Ahora `entrega_recepcion` exige una entrega, así que usa `compra`.
- **`DatosConfirmados`:** borra también entregas, renglones, costos, historial y listas de precios.

### Resultado

- `./mvnw test`: 270 tests, 0 fallas, 0 errores. Antes de este módulo eran 229.

## 7. Decisiones que no estaban en el plan

- **Costo en el local:**
  - El plan decía guardarlo neto si el local discrimina IVA, y con IVA si no.
  - Por tu pedido, ahora es el precio unitario de la entrega sin ajustes de IVA.
  - Lo anoté en "Para revisar en la etapa 3", para que lo confirme el contador.
- **Tres vistas en lugar de dos:**
  - El plan tenía `EntregaResponse` y `RecepcionEmpleadoResponse`.
  - Agregué `EntregaLocalResponse` para el dueño del local. Así la fábrica no ve la ubicación de destino y el local no ve la ubicación de origen ni la lista.
- **Auditoría en dos registros con distinto detalle:** el ámbito que no actúa no ve los datos internos del que actúa (ver punto 5).
- **Un artículo que la fábrica no ve responde 404, no 409:**
  - Es el caso de la reventa de un local o de un artículo inexistente.
  - Sigue la regla general de `CLAUDE.md`. Un insumo, un artículo sin precio o uno inactivo sí responden 409.
- **El motivo es obligatorio en las dos formas de resolver,** no solo al dar por perdido.
- **El reingreso vuelve a la ubicación de origen de la entrega:** no se elige otra.
- **La resolución no cambia el estado:** la entrega sigue en `diferencia_informada`, y lo que la marca como resuelta es `diferencia_resuelta_at`.
- **Las observaciones de la recepción son opcionales,** aunque haya faltante.
- **La recepción trae todos los renglones:** una recepción con todo en cero queda en `diferencia_informada` y no genera movimientos.
- **Un mismo artículo puede ir en dos renglones solo con distinto lote.**
- **Local o lista desactivados:** un local o una lista desactivados responden 409; uno inexistente o de otro ámbito, 404.
- **El total se verifica en la base al confirmar,** con triggers diferidos. No lo pedía el plan, pero es una garantía que puede dar la base.
- **Los tipos `anulacion` y `devolucion` exigen hoy un `entrega_id`:**
  - Si en la etapa 3 la anulación de una venta usa el tipo `anulacion`, una migración nueva tendrá que permitir `venta_id` en su lugar.
- **V9 se corrigió antes de aplicarse en ninguna base fuera de los tests:**
  - La función del total diferido fallaba al dispararse desde `entregas`, porque intentaba leer `entrega_id` de esa fila y esa tabla no tiene esa columna.
  - Se arregló en la misma V9, que nunca corrió sobre la base de desarrollo.

## 8. Pendiente

- V9 todavía no se aplicó a la base de desarrollo: se aplica la próxima vez que se levante la app.
- Etapa 3:
  - El remito, la cuenta corriente (la deuda es por lo recibido) y la resolución de diferencias con nota de crédito.
  - Confirmar con el contador el IVA en el costo.
