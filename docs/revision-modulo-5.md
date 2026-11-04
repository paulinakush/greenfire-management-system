# Revisión del módulo 5 de la etapa 2: catálogo público

## 1. Tests de lo que no se expone

Están en `backend/src/test/java/com/greenfire/catalogo/CatalogoPublicoApiTest.java`, salvo el último, que está en `ambitos/AmbitoApiTest.java`.

- **`lasCategoriasTienenSoloLasClavesPermitidas`:** cada categoría de `GET /api/publico/categorias` tiene exactamente `id`, `nombre` y `descripcion`.
- **`elCatalogoTieneSoloLasClavesPermitidas`:** en `GET /api/publico/catalogo` revisa dos niveles.
  - La página tiene exactamente `localId`, `localNombre`, `articulos`, `pagina`, `tamano` y `total`.
  - Cada artículo tiene exactamente `id`, `nombre`, `descripcion`, `categoriaId`, `categoriaNombre`, `marca`, `unidadMedida`, `imagen`, `precio` y `disponible`.
- **`elDetalleTieneSoloLasClavesPermitidas`:** `GET /api/publico/catalogo/{id}` tiene exactamente los mismos diez campos que cada artículo del listado.
- **`losLocalesTienenSoloLasClavesPermitidas`:** cada local de `GET /api/publico/locales` tiene exactamente `id`, `nombre`, `direccion`, `horarios`, `telefono`, `email` y `esCentral`.
- **`ningunaRespuestaExponeDatosInternos`:**
  - **Qué carga antes de pedir:**
    - Costos de la fábrica (4444.44) y de Palermo (3333.33).
    - Una cantidad de stock (54321), con mínimo 12345 y máximo 98765, en una ubicación llamada "Depósito Secreto".
    - La lista de la fábrica para locales, "Para locales", con el precio 7777.77.
    - Un registro de historial con el precio anterior 6666.66.
    - Otra lista de Palermo, "Mayorista secreta".
  - **Qué pide:** siete respuestas públicas.
    - Categorías y locales.
    - El catálogo de Palermo y el de Belgrano.
    - Tres detalles: el producto de la fábrica en los dos locales y la reventa en Palermo.
  - **Qué revisa en las claves:** ninguna clave, en ningún nivel del JSON, contiene costo, margen, neto, cantidad, stock, minimo, maximo, ubicacion, lista, historial, lote, movimiento, cuit, iva, fiscal, razon ni condicion.
  - **Qué revisa en el texto:** no aparece ninguno de los valores cargados, ni "Principal".
    - Tampoco aparecen los CUIT de las tres entidades, "responsable_inscripto", "monotributo" ni las razones sociales.
- **`AmbitoApiTest.elSitioPublicoMuestraSoloDatosDeContactoSinNadaFiscal`:** cada local de `GET /api/publico/locales` tiene exactamente `id`, `nombre`, `direccion`, `horarios`, `telefono`, `email` y `esCentral`.
  - Es un test que ya existía; lo actualicé para que espere también `id` y `esCentral`.

## 2. Tests de IVA

Están en `CatalogoPublicoApiTest`.

- **`enUnLocalResponsableInscriptoConListaSinIvaElPrecioPublicoSumaElIva`:**
  - **Datos:** Palermo es responsable inscripto y su lista predeterminada es sin IVA.
  - **Producto de la fábrica:** con precio 1000 y alícuota del 21 %, se publica a 1210.00.
  - **Reventa:** con precio 2000 y alícuota del 10,5 %, se publica a 2210.00.
  - Lo comprueba en el detalle y en el listado.
- **`enUnLocalResponsableInscriptoConListaConIvaElPrecioPublicoEsElDeLaLista`:**
  - **Datos:** un local responsable inscripto, Córdoba, con lista predeterminada con IVA y el precio 1210.
  - **Resultado:** el precio publicado es 1210.00, el de la lista, sin sumar nada.
- **`enUnLocalQueNoDiscriminaIvaElPrecioDeLaListaEsElPrecioFinal`:**
  - **Datos:** Belgrano es monotributista y su lista tiene el precio 1500.
  - **Resultado:** el precio publicado es 1500.00, sin cambios, en el detalle y en el listado.

## 3. Local central

- **Implementado:** sí, después de que aprobaras la propuesta. Antes de escribir código te la presenté como pregunta, y elegiste la opción recomendada.
- **Cómo se marca:**
  - Con el campo `ambitos.es_central`, booleano y en false por defecto, que agrega la migración `V8__local_central.sql`.
  - La restricción `ambitos_central_valido` exige que el central sea un local activo y visible en la web.
  - Ningún local quedó marcado.
  - Mientras no haya uno, el catálogo sin `localId` responde 400: "Elegí un local: todavía no hay un local central."
- **Quién puede cambiarlo:**
  - Solo el administrador técnico, con `POST /api/admin/ambitos/{id}/central`.
  - El dueño de fábrica y el dueño de local reciben 403, y sin sesión responde 401. Lo comprueba `LocalCentralTest.soloElAdminCambiaElCentral`.
  - En una misma transacción, el endpoint desmarca al central anterior y marca el nuevo.
  - Quedan dos registros de auditoría, cada uno con su ámbito afectado: `desmarcar_central` y `marcar_central`. Lo comprueba `elAdminMarcaElCentralYDesmarcaAlAnteriorAuditandoALosDos`.
  - Para ocultar o desactivar el central, primero hay que marcar otro; si no, responde 409. Lo comprueba `elCentralNoSeOcultaNiSeDesactivaSinMarcarAntesOtro`.
- **Qué pasa si hay dos:** la base no lo permite.
  - El índice único parcial `uq_ambitos_central` rechaza que un segundo ámbito quede con `es_central = true`.
  - Lo comprueba `LocalCentralTest.laBaseNoAdmiteDosLocalesCentrales`, con un update directo en la base.
  - Por la API no puede pasar, porque el endpoint desmarca al anterior antes de marcar el nuevo.
  - Si alguna vez el índice frenara una operación, `ManejadorErrores` respondería 409 con "Ya hay otro local central.". Ese mensaje no tiene test, porque no hay forma de llegar a ese caso por la API.

## 4. Artículo que no se vende, oculto o local no visible

- **El artículo no se vende en ese local o está oculto:**
  - **En el detalle:** responde 404 con "No existe el artículo indicado.".
    - El cuerpo es exactamente igual al de un artículo que no existe, así no se revela qué hay.
    - Lo mismo pasa si el artículo está inactivo, su categoría está inactiva o no tiene precio en la lista predeterminada del local.
  - **En el listado:** el artículo simplemente no aparece.
  - **Tests:**
    - `unArticuloQueNoSeVendeEnElLocalOEstaOcultoRespondeIgualQueUnoQueNoExiste` compara el cuerpo completo de las tres respuestas.
    - `soloAparecenArticulosActivosVisiblesYConPrecioEnLaListaPredeterminada` cubre los demás casos.
- **El local no es visible en la web:**
  - Responde 404 con "No existe el local indicado.", en el listado y en el detalle.
  - La respuesta es la misma para un local oculto, un local inactivo, el id de la fábrica y un id que no existe.
  - Lo comprueba `unLocalQueNoSeMuestraEnLaWebRespondeIgualQueUnoQueNoExiste`.
  - El mensaje es distinto del de un artículo inexistente, así que dice si el problema es el local o el artículo. Lo que no revela es si el local existe pero está oculto.
