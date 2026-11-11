# Resumen del módulo 2 de la etapa 3: backups

## 1. Qué se hizo

- **Scripts en `ops/backups/`,** fuera del código de la app:
  - `respaldar.sh`: el backup, con cifrado, verificación, retención y copia remota opcional.
  - `restaurar.sh`: la restauración en una base vacía, distinta de la de trabajo, con confirmación.
  - `probar-restauracion.sh`: la prueba de punta a punta contra la base de desarrollo.
  - `comun.sh`: las funciones que comparten.
  - `tests/probar-scripts.sh`: los tests de los scripts.
- **Carga de desarrollo:** `ops/desarrollo/puntos-venta-x.py` deja un punto de venta X activo en cada ámbito.
- **Documentación:**
  - `docs/backups.md`: el procedimiento, en lenguaje simple. Explica cómo se configura, cómo se restaura, qué esperar y cómo programarlo.
  - `.env.example`: suma las variables `BACKUP_*`, sin valores.
  - `CLAUDE.md`: suma las carpetas `ops/backups/` y `ops/desarrollo/`.
- **Sin cambios en la app:** no hay migraciones, clases ni tests de Java nuevos.

## 2. El backup (`respaldar.sh`)

- **Qué guarda:** un `pg_dump` en formato custom, comprimido, con la base y la fecha en el nombre (`<base>-AAAAMMDD-HHMMSS.dump.enc`).
- **Configuración:** todo sale de variables de entorno o del `.env`, y una variable del entorno tiene prioridad. El `.env` se lee línea por línea, sin ejecutarlo.
- **Carpeta (`BACKUP_DIR`):**
  - Ruta absoluta y fuera del repositorio. Si está adentro, el script falla sin crearla.
  - Queda con permisos 700.
- **Cifrado:**
  - AES-256 con openssl, con la clave derivada de `BACKUP_CLAVE_CIFRADO` (PBKDF2, 600.000 iteraciones). La clave tiene que tener al menos 20 caracteres.
  - El dump pasa directo de `pg_dump` a openssl: nunca se escribe sin cifrar.
  - La clave le llega a openssl por el entorno, no por la línea de comandos, así no aparece en la lista de procesos.
  - El archivo queda con permisos 600.
- **Verificación:**
  - Se hace sobre el archivo ya cifrado: que no esté vacío, que se descifre, que `pg_restore --list` lo lea y que tenga datos de alguna tabla.
  - Recién entonces pasa de `.parcial` a su nombre final. Si falla, se descarta.
- **Retención:**
  - Borra los backups de esa base con más de `BACKUP_RETENCION_DIAS` días (30 por defecto), salvo el más reciente.
  - Corre solo después de un backup verificado.
  - No toca otros archivos ni los backups de otra base.
- **Copia remota (opcional):**
  - Sube a un almacenamiento compatible con S3 con `curl --aws-sigv4`, sin instalar nada más. Después compara el tamaño remoto con el local.
  - Las credenciales le llegan a curl por la entrada estándar.
  - Sin configurar, avisa y termina bien. Configurada a medias, falla antes del backup y dice qué falta.
  - Exige `https://`, salvo para `localhost`, que se usa en los tests.
- **Bloqueo:** no deja correr dos backups a la vez en la misma carpeta.
- **Errores:** cada falla termina con un mensaje claro y un código de salida distinto según la causa (1 a 5), documentados en `docs/backups.md`.
- **Dónde corren las herramientas:** con `pg_dump`, `pg_restore` y `psql` instalados, o dentro de un contenedor de Docker (`BACKUP_PG_DOCKER`), como en esta Mac, que no las tiene.

## 3. La restauración (`restaurar.sh`)

- **Uso:** `restaurar.sh <archivo> <base_destino>`.
- **Antes de tocar nada:**
  - Rechaza la base de trabajo (`POSTGRES_DB`) y las del sistema, aunque se confirme.
  - Rechaza nombres inválidos, bases con tablas y bases con conexiones abiertas.
  - Verifica que el backup se descifra y se lee. Con otra clave, no crea la base.
- **Confirmación:** muestra el backup, el servidor y la base de destino, y pide escribir el nombre de la base de destino. Si no coincide o no hay respuesta, cancela con el código 7.
- **Restauración:**
  - Crea la base si no existe.
  - Restaura en una sola transacción, así que si falla la base queda vacía.
  - Al terminar informa cuántas tablas hay y la versión de Flyway.

## 4. Prueba de restauración con la base de desarrollo

- **Comando:** `ops/backups/probar-restauracion.sh`, con `BACKUP_PG_DOCKER` apuntando al contenedor de `docker compose`. Usó una carpeta temporal y una clave al azar, y no subió nada.
- **Backup:** `greenfire-20261110-231242.dump.enc`, de 133.056 bytes, verificado con 24 tablas con datos.
- **Restauración:** en `greenfire_prueba_restauracion`, que no existía. Quedaron 24 tablas, con Flyway en la versión 11.
- **Comparación de filas, tabla por tabla, contra la original:** las 24 tablas coinciden. Las que tienen datos:
  - `ambitos` 4, `entidades_fiscales` 4, `usuarios` 8 y `usuario_ambitos` 8.
  - `auditorias` 62, `clientes` 5 y `listas_precios` 1.
  - `ubicaciones_stock` 4 y `flyway_schema_history` 11.
  - `spring_session` 1 y `spring_session_attributes` 2.
- **La app contra la base restaurada,** en el puerto 8081:
  - Arrancó en 6,1 segundos. Flyway informó "Schema public is up to date. No migration necessary".
  - `GET /api/publico/locales` devolvió los 3 locales visibles de la base restaurada.
  - El administrador técnico entró con su contraseña de siempre y vio los 4 ámbitos.
- **Al terminar:** borró la base temporal y el backup de prueba. Quedaron solo `greenfire` y las bases del sistema.
- **Momento de la prueba:** se hizo antes de cargar los puntos de venta, por eso `puntos_venta` tenía 0 filas.

## 5. Puntos de venta X en la base de desarrollo

- **Cómo se cargaron:** con `ops/desarrollo/puntos-venta-x.py`, por la API, como administrador técnico. Las credenciales se leen del entorno o del `.env` y no se imprimen.
- **Resultado:** un punto de venta número 1, activo, en la Fábrica, el Local La Rioja, el Local Mitre y el Local Ruta 1. Cada uno con presupuesto X, recibo X y remito X.
- **Se puede repetir:** la segunda corrida respondió "sin cambios" en los cuatro. Si un punto de venta está desactivado lo activa, y si le faltan tipos X se los suma.
- **Solo desarrollo:** se niega a correr si la API no es `localhost`.
- **Lo que falta, para el módulo 3:**
  - Hoy no existe `comprobante_venta`, y la nota de crédito X no se admite (`ReglasComprobante` trata la nota de crédito como fiscal).
  - Cuando el módulo 3 los sume, se agregan a `TIPOS_X` y se vuelve a correr el script.

## 6. Tests

- **`ops/backups/tests/probar-scripts.sh`: 56 pasan, 0 fallan.**
  - No tocan la base de desarrollo ni el `.env`: levantan un PostgreSQL 16 descartable en Docker, con carpetas temporales y una clave al azar.
  - Al terminar no quedan contenedores ni carpetas.
- **Configuración:** fallan con código 1, y sin dejar backups:
  - Sin clave, o con una clave corta.
  - Con la carpeta dentro del repositorio (y no la crean) o con una ruta relativa.
  - Con la copia remota a medias.
  - Con una retención inválida.
  - Con un endpoint `http` fuera de la máquina.
- **Backup:**
  - El nombre lleva la fecha y el archivo queda con permisos 600, en una carpeta con permisos 700.
  - El archivo está cifrado: no tiene la firma de `pg_dump` ni el texto de los datos.
  - Con la clave, `pg_restore` lo lee; con otra, no.
  - No deja archivos parciales ni el bloqueo.
- **Fallas:**
  - Si `pg_dump` falla, termina con código 2 y no deja archivos.
  - Una base sin datos no pasa la verificación (código 3).
  - Un archivo cortado o vacío no pasa la verificación.
  - Con otro backup en curso, no arranca.
- **Retención:**
  - Borra los backups viejos y conserva los recientes.
  - No toca otros archivos ni los de otra base.
  - Si todos son viejos, conserva el más reciente.
  - El backup la aplica al terminar.
- **Copia remota,** con un servidor S3 de prueba local:
  - Sube el archivo entero, al bucket y prefijo configurados, firmado con AWS SigV4 y la región configurada.
  - Si la subida falla, termina con código 4 y el backup local queda.
- **Restauración:**
  - Rechaza la base de trabajo, una del sistema, un nombre inválido y una base con tablas.
  - Una confirmación equivocada o vacía cancela (código 7) sin crear la base.
  - Con otra clave no restaura (código 3).
  - Confirmando, restaura en una base nueva con las mismas filas, sin tocar la de trabajo, y también en una base existente y vacía.
  - Volver a restaurar sobre una base con datos falla.
- **Tests de la app:** no se corrieron, porque este módulo no cambió nada del backend.

## 7. Decisiones que tomé y conviene revisar

- **Cifrado con openssl,** porque viene en cualquier servidor y en la Mac.
  - No tiene una firma que detecte una modificación del archivo, pero un archivo alterado no pasa `pg_restore`.
  - Si se prefiere cifrado con firma, la alternativa es `age` o `gpg`, que hay que instalar.
- **Copia remota con curl, sin la CLI de AWS ni la de B2,** para no sumar dependencias.
  - Se probó con un servidor S3 de prueba local, no contra Backblaze B2 de verdad, porque todavía no hay cuenta ni hosting (punto 8.13).
  - Conviene probarla con el bucket real antes de cargar datos reales.
- **El script no borra nada en la copia remota.** La retención remota se configura en el bucket, y la clave de la aplicación conviene que no tenga permiso de borrar.
- **La restauración pide escribir el nombre de la base,** no un "sí". Así es difícil confirmar por error, y se puede automatizar en la prueba pasando el nombre por la entrada estándar.
- **La carga de puntos de venta está en el repositorio,** en `ops/desarrollo/`. No tiene datos de prueba, credenciales ni datos reales: solo la regla de un punto de venta X por ámbito, con número 1 y la descripción "Punto de venta X (desarrollo)". Si preferís que quede afuera, como las otras cargas, la saco.
- **Nada quedó programado:** `docs/backups.md` explica cómo hacerlo con cron o con launchd, pero no se activó ninguno.

## 8. Pendiente

- **Definir el hosting (punto 8.13):** de eso depende dónde corre el backup diario y si conviene un servicio que ya incluya backups.
- **Antes de cargar datos reales:**
  - Crear el bucket y la clave en B2 y probar la copia remota de verdad.
  - Guardar la clave de cifrado fuera del servidor.
  - Programar el backup diario y correr `probar-restauracion.sh` en el servidor.
- **Módulo 3 (comprobantes):** no se empezó.
