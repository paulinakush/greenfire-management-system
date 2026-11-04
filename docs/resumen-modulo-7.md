# Resumen del módulo 7 de la etapa 2: importación CSV de artículos y precios

## 1. Qué se hizo

- **Paquete nuevo `importacion`:**
  - `LectorCsv`, que lee el archivo: codificación, separador, comillas, encabezado y límites.
  - `Numero`, que lee los decimales con coma o con punto.
  - `ImportacionArticulosService`, `ImportacionPreciosService` e `ImportacionController`.
  - DTOs: `ResultadoImportacion` y `FilaImportacion`.
  - Excepciones: `ImportacionConErroresException` (409 con el detalle por fila) y `ArchivoDemasiadoGrandeException` (413).
- **Base de datos:** sin migración ni cambios en el DBML, porque la importación usa las tablas de los módulos 1 y 2.
- **Cambios en otros paquetes:**
  - `ArticuloRepository` suma `findByAmbitoId` y `findByTipo`.
  - `ListaPreciosService.buscarActiva` y `guardarHistorial` pasan a ser públicos, para reutilizar las mismas reglas y el mismo historial.
  - `ManejadorErrores` responde 413 a un archivo demasiado grande y 400 cuando falta el archivo.
  - `application.yml` sube el límite de multipart a 2 MB, así el de 1 MB lo controla la importación, con su mensaje.
- **`CLAUDE.md`:**
  - Suma el paquete `importacion`.
  - Suma la sección "Importación desde planilla", con la nota sobre los textos que empiezan con `=`, `+`, `-` o `@`.

## 2. Endpoints

- **Vista previa de artículos:** `POST /api/importacion/articulos/vista-previa`. No guarda nada.
- **Confirmación de artículos:** `POST /api/importacion/articulos`. Aplica el archivo todo o nada.
- **Vista previa de precios:** `POST /api/importacion/precios/vista-previa?listaId=`.
- **Confirmación de precios:** `POST /api/importacion/precios?listaId=`.
- **Cómo se envía el archivo:** como multipart, en el campo `archivo`, con el token CSRF como en cualquier POST.
- **Qué devuelven:** `archivo`, `aplicado`, `filas`, `crear`, `actualizar`, `sinCambios`, `errores` y `detalle`.
  - Cada fila de `detalle` trae `fila` (el número en la planilla, contando el encabezado como fila 1), `accion` (`crear`, `actualizar`, `sin_cambios` o `error`), `nombre` y `motivo`.
  - En artículos trae además `campos`, la lista de campos que cambian.
  - En precios trae `precioAnterior` y `precioNuevo`.
- **Confirmación con errores:** si alguna fila tiene error, responde 409 con el mismo detalle y `"aplicado": false`, y no aplica ninguna fila.

## 3. Permisos

- **Dueño de fábrica y dueño de local:** importan, cada uno en su ámbito.
- **Empleado y administrador técnico:** reciben 403.
- **Sin sesión:** 401.
- **Orden de las validaciones:** el acceso se valida antes de leer el archivo.

## 4. El archivo

- **Codificación:** UTF-8, con o sin BOM. En otra codificación responde 400: "El archivo tiene que estar en UTF-8. Desde la planilla, guardalo como \"CSV UTF-8\"."
- **Separador:**
  - Coma o punto y coma. Se toma de la fila de encabezado: el que más aparece fuera de comillas.
  - Los valores entre comillas pueden llevar el separador, comillas dobles (`""`) y saltos de línea.
  - Acepta fin de línea `\n` o `\r\n`.
- **Decimales:**
  - Con coma o con punto, sin separador de miles.
  - Con separador coma, un decimal con coma va entre comillas (`"10,5"`), como lo guarda la planilla.
  - Un número con coma y punto a la vez (`1.234,50`) es un error de la fila.
  - En los precios, `1.000` también es un error de la fila: tiene más de dos decimales, y así no se confunde con mil.
- **Encabezado:**
  - Se aceptan mayúsculas, tildes y espacios: "Alícuota IVA" se lee como `alicuota_iva`.
  - Una columna que no corresponde, repetida o faltante responde 400, con la lista de columnas válidas.
- **Límites:**
  - Hasta 1 MB. Si es más grande, responde 413: "El archivo supera el máximo de 1024 KB."
  - Hasta 2000 filas de datos. Con más, responde 400: "El archivo supera el máximo de 2000 filas."
  - Las filas vacías se saltean.
- **Errores de fila:** una fila con más columnas que el encabezado es un error de esa fila.

## 5. Artículos

- **Columnas:**
  - Obligatorias: `nombre`, `tipo`, `unidad` y `alicuota_iva`.
  - Opcionales: `categoria`, `marca`, `descripcion`, `imagen` y `visible_web`.
- **Qué importa cada dueño:**
  - El dueño de fábrica, insumos y productos terminados.
  - El dueño de local, solo la reventa de su local.
  - Otro tipo es un error de la fila.
- **Identificación:**
  - Un artículo se identifica por su nombre dentro del ámbito, sin distinguir mayúsculas.
  - Si existe se actualiza; si no, se crea.
  - Si un local usa el nombre de un producto de la fábrica, crea su propia reventa, porque el nombre es único por ámbito.
- **Categoría:**
  - Se busca por nombre, sin distinguir mayúsculas, y tiene que existir y estar activa.
  - Es obligatoria salvo en los insumos, que no la llevan.
- **Unidad:** tiene que ser una de las existentes (unidad, kg, litro, m3 o dm3); si no, es un error de la fila.
- **Tipo:** se acepta "producto terminado" con espacio. En un artículo existente no cambia: un tipo distinto es un error.
- **Validaciones de cada fila:**
  - Las mismas reglas que el alta por API: alícuota 0, 10.5, 21 o 27; imagen dentro de `assets/productos/`; un insumo no es visible en la web; largos máximos.
  - `visible_web` admite sí, no, true, false, 1, 0 o x.
- **Nombres repetidos:** un mismo nombre en dos filas del archivo es un error en la segunda, que dice en qué fila está la primera.
- **Actualización:** una celda opcional vacía deja el valor como está, así que con la importación no se borra un valor.

## 6. Precios

- **Columnas:** `articulo` y `precio`.
- **Lista:** se cargan en una lista activa del propio ámbito.
  - Una lista de otro ámbito responde 404.
  - Una lista desactivada responde 409.
- **Artículo:**
  - Se busca por nombre, sin distinguir mayúsculas, entre los que el ámbito ve: los suyos y, en un local, los productos terminados de la fábrica.
  - Un artículo que no existe, que es de otro local o que es un insumo de la fábrica da el mismo error: "No existe el artículo 'X'.". Así no se revela qué hay en otro ámbito.
  - Un artículo desactivado es un error de la fila.
- **Nombre ambiguo:**
  - Si un local tiene una reventa con el mismo nombre que un producto de la fábrica, la fila es un error que pide renombrar la reventa.
  - No se elige uno de los dos por las suyas.
- **Precio:** no negativo y con hasta dos decimales.
- **Qué pasa con cada fila:**
  - Un artículo sin precio en la lista se crea.
  - Uno con otro precio se actualiza.
  - Uno con el mismo precio queda sin cambios: no deja historial.
- **Historial:** cada precio que cambia queda en `historial_precios`, con el usuario de la sesión, igual que un cambio por la API.

## 7. Todo o nada, auditoría y textos que parecen fórmulas

- **Vista previa:** corre en una transacción de solo lectura y no guarda nada. Tampoco audita.
- **Confirmación:**
  - Vuelve a validar el archivo entero, porque algo pudo cambiar desde la vista previa.
  - Si alguna fila tiene error, responde 409 sin escribir nada.
  - Si algo falla después de empezar a guardar, se revierte toda la transacción. Lo prueba `ImportacionAtomicidadTest`.
- **Auditoría:** cada importación aplicada queda en un solo registro, con el ámbito como afectado.
  - **Artículos:** `importar_articulos`, entidad `importacion`.
    - El detalle lleva el nombre del archivo y las cantidades de creadas, actualizadas y sin cambios.
    - Lleva también, por cada fila aplicada, el número de fila, el artículo y los cambios con el valor anterior y el nuevo.
  - **Precios:** `importar_precios`, entidad `lista_precios`, con el id de la lista.
    - El detalle lleva el archivo, las cantidades y, por cada precio, el anterior y el nuevo.
- **Textos que empiezan con `=`, `+`, `-` o `@`:**
  - No se rechazan y se guardan tal cual.
  - Quedó anotado en `CLAUDE.md` que, si algún día se exportan a planilla, hay que neutralizarlos.
  - Lo prueba `unTextoQueEmpiezaComoFormulaSeGuardaTalCual`.

## 8. Tests nuevos (19)

### `ImportacionArticulosApiTest` (11)

- `unArchivoValidoCreaYActualizaArticulos`:
  - La vista previa da 2 a crear y 1 a actualizar, con los campos que cambian.
  - La confirmación los guarda y deja un solo registro de auditoría, con el ámbito, el archivo, las creadas y las actualizadas.
  - El artículo existente se encuentra con otro uso de mayúsculas.
- `elLocalImportaSuReventa`.
- `unaFilaConErrorNoAplicaNinguna`: dos filas con error (una categoría y una unidad que no existen) con su número y su motivo; la confirmación responde 409 y no crea nada ni audita.
- `cadaDuenoImportaSoloLoQueAdministra`: el local no importa productos terminados y la fábrica no importa reventa; un local puede usar el nombre de un producto de la fábrica.
- `laCategoriaTieneQueExistirYEstarActivaYElTipoNoCambia`: cubre la categoría desactivada o faltante, el tipo que no cambia, un insumo con categoría, la alícuota inválida y un nombre repetido en el archivo.
- `aceptaComaOPuntoYComaComoSeparadorYDecimalesConComaOPunto`: prueba el punto y coma con `10,5`, la coma con `10.5` y con `"10,5"` y los fines de línea `\r\n`. Prueba también un archivo con BOM, encabezado con tildes y un valor con el separador entre comillas.
- `elArchivoTieneQueEstarEnUtf8YConLasColumnasQueCorresponden`: rechaza un archivo en ISO-8859-1, uno sin una columna obligatoria, uno con una columna que no corresponde y uno sin filas.
- `unArchivoDemasiadoGrandeOConDemasiadasFilasSeRechazaConUnMensajeClaro`: un archivo de más de 1 MB responde 413 y uno de 2001 filas, 400.
- `unTextoQueEmpiezaComoFormulaSeGuardaTalCual`.
- `laVistaPreviaNoCambiaNadaEnLaBase`: compara una foto de los artículos (incluido `updated_at`) y de la auditoría antes y después.
- `elEmpleadoYElAdministradorReciben403YSinSesion401`.

### `ImportacionPreciosApiTest` (6)

- `unArchivoValidoCargaLosPreciosYDejaHistorialConElUsuario`: un precio se crea y otro se actualiza; el historial queda con el usuario y la auditoría con la lista, el archivo y las cantidades.
- `unArticuloDeOtroAmbitoEsElMismoErrorQueUnoQueNoExiste`:
  - Prueba la reventa de otro local, un nombre inexistente y un insumo de la fábrica: los tres dan el mismo mensaje.
  - Con una de esas filas, la confirmación no cambia ningún precio ni deja historial.
- `losPreciosAceptanComaOPuntoPeroNoSeparadorDeMiles`: rechaza `1.234,50`, `1.000`, un precio negativo y un texto.
- `laListaTieneQueSerDelPropioAmbitoYEstarActiva`.
- `laVistaPreviaNoCambiaNingunPrecio`.
- `elEmpleadoYElAdministradorReciben403YSinSesion401`.

### `ImportacionAtomicidadTest` (transacciones reales, 2)

- `siFallaDespuesDeGuardarLosArticulosNoQuedaNinguno`:
  - Falla el último paso, la auditoría, cuando los artículos ya se insertaron. Responde 500 y no queda ninguno.
  - Sin la falla, el mismo archivo se aplica.
- `siFallaDespuesDeGuardarLosPreciosNoQuedaNingunoNiSuHistorial`: hace lo mismo con una importación de precios.

### Resultado

- `./mvnw test`: 289 tests, 0 fallas y 0 errores. Antes de este módulo eran 270.

## 9. Decisiones que no estaban en el plan

- **La confirmación vuelve a subir el archivo:**
  - Elegí que la vista previa no deje nada guardado del lado del servidor: ni el archivo ni un token.
  - La confirmación lo valida de nuevo. Si algo cambió desde la vista previa, la confirmación lo ve y responde 409.
- **Columnas fijas:** una columna que no corresponde responde 400, en lugar de ignorarse, para que un nombre mal escrito no se pierda sin aviso.
- **En una actualización, una celda opcional vacía no cambia el valor:** la importación no sirve para borrar una marca o una descripción.
- **Límites:** 1 MB y 2000 filas. El archivo demasiado grande responde 413; las demás fallas del archivo, 400.
- **Una sola auditoría por importación,** con el detalle de cada fila y sus cambios, en lugar de un registro por artículo.
- **Nombre de categoría y de lista:** se buscan sin distinguir mayúsculas. La lista se elige por id (`listaId`), no por nombre.
- **Formatos aceptados:** se aceptan tildes y mayúsculas en el encabezado y en los valores de tipo y unidad, y "sí", "x" o "1" en `visible_web`.
- **Nombres ambiguos en precios:** son un error de la fila, en lugar de elegir uno.
- **Qué no se importa:** activar o desactivar artículos y quitar precios siguen siendo solo por API.

## 10. Pendiente

- **Front:** el front de la importación queda para cuando se conecte `privado/`.
- **Exportación a planilla (etapa 5):** al exportar, neutralizar los textos que empiezan con `=`, `+`, `-` o `@` (ver `CLAUDE.md`).
