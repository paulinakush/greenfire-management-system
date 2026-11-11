# Resumen del módulo 1 de la etapa 3: clientes

## 1. Qué se hizo

- **Plan:** `docs/plan-etapa-3.md`, con los ajustes de la dueña y los técnicos, en el commit `ec41c8f` de la rama `etapa-3-clientes-ventas-caja`. En el mismo commit:
  - `docs/complemento.md`: el dueño de fábrica vende al por mayor y opera la cuenta de sus mayoristas.
  - `CLAUDE.md`: regla 11, "la fábrica no vende al público minorista".
- **Modelo:** `docs/modelo-datos.dbml`, actualizado antes que la migración. La tabla `clientes`:
  - Suma `ambito_id`, `cuenta_corriente_habilitada` y `limite_credito`.
  - Saca `password_hash`: las cuentas web van en una tabla aparte, en una etapa posterior.
  - Documenta sus índices y las reglas de cada columna.
- **Migración:** `V11__clientes.sql`, una migración nueva. V1 a V10 no se tocaron.
- **Paquete nuevo `clientes`:**
  - Entidad y repositorio: `Cliente`, con `@DynamicUpdate`, y `ClienteRepository`, sin borrado.
  - Enum `TipoDocumento`, con los valores `cuit` y `dni`.
  - `ClienteService` y `ClienteController`.
  - DTOs de pedido: `ClienteRequest` (datos y condiciones), `ClienteDatosRequest` y `ClienteCondicionesRequest`.
  - DTOs de respuesta, uno por perfil: `ClienteResponse` para los dueños y `ClienteEmpleadoResponse` para el empleado, los dos con la interfaz `ClienteVista`. Además, `PaginaClientes`.
- **Tests:**
  - `Datos` suma `cliente(ambito, nombre)` y `clienteDelLocal(local)`.
  - Tests nuevos: `ClienteApiTest` y `ClienteMigracionTest`.
- **Documentación:** `CLAUDE.md` suma el paquete `clientes`.

## 2. La base de datos (V11)

- **Tabla `clientes`:**
  - `ambito_id` obligatorio: el ámbito dueño del cliente.
  - `condicion_iva` con CHECK, `consumidor_final` por defecto.
  - Importes en `numeric(14,2)`.
- **CHECKs:**
  - `clientes_nombre_o_razon_social`: el nombre o la razón social, al menos uno, sin contar espacios.
  - `clientes_documento_valido`:
    - Tipo y número van juntos, o ninguno de los dos.
    - CUIT de 11 dígitos y DNI de 7 u 8.
    - Usa `coalesce` para que un null no deje pasar un documento a medias.
  - `clientes_cuenta_corriente_con_documento`: no hay cuenta corriente sin documento.
  - `clientes_local_sin_limite`: la cuenta de un local con la fábrica está siempre habilitada y no tiene límite.
  - Límite de crédito y plazo de pago, no negativos.
- **Índices:**
  - `uq_clientes_documento`: el documento es único dentro del ámbito.
    - Es un índice parcial: no cuenta los clientes que representan a un local, porque dos locales pueden compartir entidad fiscal.
  - `uq_clientes_local_representado`: un cliente por local.
- **Trigger `clientes_validar`:**
  - El ámbito del cliente y el local que representa no cambian.
  - La lista de precios es del mismo ámbito que el cliente.
  - Solo la fábrica tiene clientes que representan a un local.
- **Sin borrado:** DELETE y TRUNCATE se rechazan, porque los clientes se desactivan.
- **El cliente de cada local en la fábrica:**
  - **Cuándo se crea:** al dar de alta un local, si ya hay fábrica. Al dar de alta la fábrica, se crean los de los locales que ya existen.
  - **Cómo se crea:** con el nombre del local y la razón social, el CUIT y la condición frente al IVA de su entidad. Lleva cuenta corriente habilitada.
  - **Se mantiene al día:** si el administrador técnico cambia el nombre del local, o la razón social, el CUIT o la condición de su entidad, el cliente se actualiza.
  - **Existentes:** la migración los crea para los locales que ya existen.

## 3. Endpoints y permisos

- **Endpoints:**
  - Consultar: `GET /api/clientes?q=&activo=&pagina=&tamano=` y `GET /api/clientes/{id}`. La búsqueda es por nombre, razón social o documento, ordenada por nombre, con 50 por página y un máximo de 100.
  - Alta: `POST /api/clientes`, con `datos` y, opcionalmente, `condiciones`.
  - Datos: `PUT /api/clientes/{id}`, para identificación y contacto.
  - Condiciones: `PUT /api/clientes/{id}/condiciones`, para cuenta corriente, límite, lista y plazo.
  - Activar y desactivar: `POST /api/clientes/{id}/activar|desactivar`.
- **Dueño de local:**
  - Todo sobre los clientes de su local.
  - Es el único del local que fija las condiciones comerciales.
- **Empleado:**
  - Ve solo los clientes activos, sin límite, lista ni plazo. Sí ve si el cliente tiene cuenta corriente.
  - Da de alta clientes y edita sus datos de contacto.
  - Si manda condiciones, edita condiciones o desactiva, recibe 403.
  - Un cliente inactivo no existe para él: 404.
- **Dueño de fábrica:**
  - Ve los clientes de la fábrica: los locales y sus mayoristas.
  - Da de alta, edita y desactiva a los mayoristas, con cuenta corriente, límite, lista y plazo.
  - De un local, solo cambia el plazo y la lista. Editar sus datos, deshabilitarle la cuenta corriente, ponerle límite o desactivarlo mientras el local está activo responde 409.
- **Administrador técnico:** no ve clientes (403).
- **Otros ámbitos:** pedir o editar un cliente de otro ámbito devuelve 404. Un local tampoco ve su propio cliente en la fábrica, porque es un dato de la fábrica.

## 4. Reglas en el servicio

- **Documento:**
  - Va solo con números.
  - El CUIT se valida con el dígito verificador (`CuitValidator`) y el DNI tiene 7 u 8 dígitos.
  - Repetido en el mismo ámbito, responde 409. En otro ámbito se permite.
- **Cuenta corriente:**
  - Exige documento, al habilitarla y también al editar los datos: no se le puede sacar el documento a un cliente con cuenta.
- **Lista de precios:**
  - Tiene que ser del ámbito del cliente y estar activa. La de otro ámbito no existe para él (404); una desactivada responde 409.
- **Textos:** se guardan sin espacios al principio y al final, y uno vacío se guarda como null.
- **Auditoría:**
  - Se audita el alta (`crear`), la edición de datos (`editar`), la de condiciones (`editar_condiciones`), y la activación y desactivación.
  - Cada edición guarda solo los campos que cambiaron, con el valor anterior y el nuevo (`Cambios`).
  - Ámbito afectado: el del cliente. En el cliente de un local, también ese local, así su dueño ve en auditoría los cambios de su plazo o su lista con la fábrica.

## 5. Tests

- **`./mvnw test`:** 328 tests, todos pasan. Son 27 nuevos de clientes y ninguno de los anteriores se modificó.
- **`ClienteApiTest` (19 tests):**
  - **Qué ve cada ámbito:** cada local ve solo sus clientes, y uno de otro local da 404 al pedirlo, editarlo o desactivarlo. El administrador técnico recibe 403.
  - **Búsqueda:** por nombre (sin distinguir mayúsculas; las tildes sí cuentan), razón social y documento, con paginación.
  - **Dueño de local:**
    - Alta con cuenta corriente, límite, lista y plazo, que queda en auditoría con el ámbito afectado.
    - Sin condiciones, el cliente es consumidor final y sin cuenta corriente.
    - La edición audita solo lo que cambió.
    - La lista de otro ámbito da 404 y una desactivada, 409.
    - Desactiva y reactiva clientes.
  - **Empleado:**
    - Ve solo los activos, sin límite, lista ni plazo.
    - Da de alta y edita datos.
    - Si manda condiciones, edita condiciones o desactiva, recibe 403.
  - **Validaciones:**
    - Nombre o razón social obligatorios.
    - CUIT con dígito verificador incorrecto, DNI corto, tipo desconocido, número sin tipo o con puntos: 400.
    - Documento repetido en el local: 409. En otro local se permite.
    - La cuenta corriente exige documento, también al editar.
  - **Fábrica:**
    - Tiene un cliente por local, con los datos de su entidad, que el local no ve.
    - Da de alta a un mayorista con su lista, cuenta corriente, límite y plazo. Una lista de un local no le sirve.
    - De un local solo cambia el plazo y la lista.
    - El cambio de condiciones de un local queda en auditoría para la fábrica y para el local.
    - Los datos del cliente siguen a los del local y de su entidad cuando los cambia el administrador técnico.
- **`ClienteMigracionTest` (8 tests), con SQL directo:**
  - La base crea el cliente de un local, y también los de los locales existentes si la fábrica se crea después.
  - Un cliente no se borra y su ámbito no cambia.
  - La lista tiene que ser del ámbito del cliente.
  - Solo la fábrica tiene clientes que representan a un local.
  - Los formatos de documento se validan, incluido el caso con null.
  - La cuenta corriente exige documento.
  - El documento es único dentro del ámbito.

## 6. Decisiones que tomé y conviene revisar

- **Datos y condiciones van separados:**
  - `PUT /api/clientes/{id}` cambia los datos y `PUT /api/clientes/{id}/condiciones` las condiciones.
  - Así el empleado edita contactos sin poder tocar el límite, y el permiso queda claro por endpoint.
- **El empleado ve si el cliente tiene cuenta corriente,** pero no el límite, la lista ni el plazo. Lo necesita para saber, en la venta, si puede vender a cuenta. El límite se valida en el servidor.
- **El local no ve su cliente en la fábrica.** Su cuenta con la fábrica la va a ver en el módulo 8, por `GET /api/cuenta-fabrica`.
- **El historial de compras y pedidos del cliente** (`GET /api/clientes/{id}/historial`, en el plan) no se hizo, porque todavía no hay ventas ni pedidos. Se agrega con el módulo de ventas.
- **Desactivar un local no desactiva su cliente en la fábrica.** Una vez desactivado el local, el dueño de fábrica puede desactivar el cliente.

## 7. Pendiente

- **Base de desarrollo:** la prueba contra la base de desarrollo, con V11 aplicada sobre sus datos, está en `docs/prueba-etapa-3-modulo-1.md`.
- **Front:** sin cambios. Como dice el plan, la conexión de `sucursal.html` es la tarea 6, después de ventas.
- **Módulo 2 (backups):** no se empezó.
