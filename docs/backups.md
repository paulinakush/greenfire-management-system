# Backups de la base de datos

Este documento explica cómo se hacen los backups del sistema, cómo se restaura uno y cómo programarlos. Los scripts están en `ops/backups/`.

## 1. Qué hace el backup

- **Una copia completa de la base:** `ops/backups/respaldar.sh` hace un `pg_dump` comprimido de la base (`POSTGRES_DB`).
- **Cifrada:** los backups tienen datos comerciales sensibles, así que se cifran antes de llegar al disco.
  - Usa AES-256 con una clave que sale de `BACKUP_CLAVE_CIFRADO`.
  - El backup sin cifrar nunca se escribe en el disco.
  - Cada archivo queda con permisos de lectura solo para el usuario (600), en una carpeta también solo suya (700).
- **Con la fecha en el nombre:** por ejemplo, `greenfire-20261110-030000.dump.enc` (base, día y hora).
- **Verificado:** después de cada backup, el script comprueba que el archivo no está vacío, que se descifra y que `pg_restore --list` lo lee y encuentra datos. Si algo falla, lo descarta y termina con error.
- **Con retención:** conserva los backups de los últimos 30 días (`BACKUP_RETENCION_DIAS`) y borra los más viejos.
  - El más reciente no se borra nunca, aunque sea viejo.
  - Solo toca archivos con el nombre de los backups de esa base.
  - La retención corre recién después de que el backup nuevo pasó la verificación. Si el backup falla, no se borra nada.
- **Con copia fuera del servidor (opcional):** si están configuradas las variables `BACKUP_S3_*`, sube el backup a un almacenamiento compatible con S3, como Backblaze B2, y comprueba que llegó entero.
  - Si no están configuradas, el script avisa y termina bien.
  - Si están a medias, falla antes de empezar y dice cuáles faltan.

## 2. Configuración

Todo sale de variables de entorno. El script también lee el `.env` de la raíz del repositorio, o el archivo que indique `GREENFIRE_ENV_FILE`. Una variable que ya está en el entorno tiene prioridad sobre el archivo. La lista completa, sin valores, está en `.env.example`.

- **Conexión:** las mismas variables de la app: `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `DB_HOST` y `DB_PORT`.
- **`BACKUP_DIR`:** la carpeta de los backups.
  - Tiene que ser una ruta absoluta, fuera del repositorio. Si está adentro, el script falla.
  - Si no existe, la crea.
- **`BACKUP_CLAVE_CIFRADO`:** la clave de cifrado, de al menos 20 caracteres.
  - **Guardala también fuera del servidor,** por ejemplo en un gestor de contraseñas. Si se pierde el servidor y con él la clave, los backups no se pueden abrir.
  - Si la cambiás, los backups viejos se siguen abriendo solo con la clave vieja.
- **`BACKUP_RETENCION_DIAS`:** los días que se conservan. Por defecto, 30.
- **`BACKUP_PG_DOCKER` (opcional):** si `pg_dump`, `pg_restore` y `psql` no están instalados en el servidor, el script los corre dentro de este contenedor de Docker. En desarrollo es el contenedor de `docker compose`, que se ve con `docker compose ps -q postgres`.
  - Si las herramientas están instaladas, tienen que ser de PostgreSQL 16 o más nuevas.
- **Copia remota (opcional):** se completan todas o ninguna.
  - `BACKUP_S3_ENDPOINT`: la dirección del servicio, con `https://`. En Backblaze B2 aparece en los datos del bucket, como "Endpoint".
  - `BACKUP_S3_REGION`: la región, que es la parte del endpoint que va después de `s3.` (por ejemplo, `us-west-004`).
  - `BACKUP_S3_BUCKET`: el nombre del bucket.
  - `BACKUP_S3_PREFIJO` (opcional): una carpeta dentro del bucket, terminada en `/`.
  - `BACKUP_S3_KEY_ID` y `BACKUP_S3_SECRET`: la clave de aplicación.
- **Recomendaciones para la copia remota:**
  - El bucket, privado.
  - La clave de aplicación, solo para ese bucket y, si se puede, solo con permiso de escritura y lectura, sin permiso de borrar. Así, si alguien entra al servidor, no puede borrar las copias remotas.
  - La retención remota se configura en el bucket (en B2, "Lifecycle Settings"), por ejemplo para borrar los archivos de más de 30 días. El script no borra nada en la copia remota.
- **Requisitos del servidor:** bash, openssl 1.1.1 o más nuevo, y curl 7.75 o más nuevo para la copia remota.

## 3. Hacer un backup a mano

```sh
ops/backups/respaldar.sh
```

- **Qué se ve:** cada paso con fecha y hora. Al final, el nombre del archivo, su tamaño y cuántas tablas con datos tiene, y "Listo.".
- **Código de salida:**
  - 0: todo bien, aunque la copia remota no esté configurada (en ese caso, con un aviso).
  - 1: falta configuración o está mal. No se hizo el backup.
  - 2: falló `pg_dump` o el cifrado. No se guardó ningún backup nuevo.
  - 3: el backup no pasó la verificación y se descartó.
  - 4: falló la copia remota. El backup quedó guardado en el servidor.
  - 5: falló la retención. El backup nuevo quedó guardado.
- **Si dice "Ya hay un backup en curso":** si no hay ninguno corriendo (por ejemplo, después de un corte de luz), borrá la carpeta `.respaldo-en-curso` dentro de `BACKUP_DIR`.

## 4. Restaurar un backup

La restauración nunca se hace sobre la base de trabajo. Siempre va a una base nueva, o a una existente y vacía, y después se decide qué hacer con ella.

### Pasos

1. **Elegí el backup.** Están en `BACKUP_DIR`, con la fecha en el nombre. Si el servidor se perdió, bajalo de la copia remota (en B2, desde la web del bucket).
2. **Asegurate de tener la clave de cifrado** con la que se hizo ese backup, en `BACKUP_CLAVE_CIFRADO`.
3. **Corré el script** con el archivo y el nombre de una base nueva:

   ```sh
   ops/backups/restaurar.sh /ruta/al/greenfire-20261110-030000.dump.enc greenfire_restaurada
   ```

4. **Confirmá.** El script muestra el backup, el servidor y la base de destino, y pide que escribas el nombre de la base de destino. Si no coincide, no hace nada.
5. **Esperá el resultado.** Al terminar dice cuántas tablas hay en la base restaurada y la versión de Flyway.

### Qué controla el script

- **Rechaza la base de trabajo** (`POSTGRES_DB`) y las del sistema, aunque se confirme.
- **Rechaza una base que no está vacía** o que tiene conexiones abiertas.
- **Verifica el backup antes de tocar nada:** si la clave no es la correcta o el archivo está dañado, no crea la base.
- **Restaura en una sola transacción:** si algo falla a mitad de camino, la base queda vacía, como estaba.
- **Código de salida:** 0 si salió bien; 1 si falta configuración o la base no sirve; 3 si el backup no se puede leer; 6 si falló la restauración; 7 si no se confirmó.

### Después de restaurar

- **Para revisar datos viejos:** la base restaurada queda al lado de la de trabajo. Se consulta con `psql` y se borra cuando ya no hace falta.
- **Para reemplazar la base de trabajo** (por ejemplo, si se dañó):
  1. Pará la app.
  2. Cambiá `POSTGRES_DB` en el `.env` por el nombre de la base restaurada.
  3. Levantá la app. Flyway valida el esquema y, si el backup es de una versión anterior, aplica las migraciones que falten.
  4. Probá que entren el administrador técnico y un dueño, y que se vean los datos del último día.
  5. La base vieja no se borra hasta estar seguros.
- **Qué esperar:**
  - Los datos son los del momento del backup: lo cargado después se pierde.
  - Las sesiones abiertas en ese momento siguen en la base, pero vencen solas a los 30 minutos sin actividad.
  - Los usuarios entran con las contraseñas que tenían en ese momento.

## 5. Probar que la restauración funciona

```sh
ops/backups/probar-restauracion.sh
```

- **Qué hace:**
  1. Cuenta las filas de cada tabla de la base de trabajo y hace un backup en una carpeta temporal, con una clave al azar. No usa la carpeta ni la clave de los backups reales, ni sube nada a la copia remota.
  2. Lo restaura en una base temporal (`<POSTGRES_DB>_prueba_restauracion`).
  3. Compara las filas de cada tabla con las de la original.
  4. Compila la app y la levanta contra la base temporal en el puerto 8081 (`PRUEBA_PUERTO`). Comprueba que Flyway valida el esquema, que la web pública devuelve los locales y, si están `ADMIN_USUARIO` y `ADMIN_PASSWORD`, que el administrador técnico entra.
  5. Para la app y borra la base temporal y el backup de prueba.
- **La base de trabajo solo se lee.**
- **Cuándo correrla:** antes de cargar datos reales, después de cambiar de servidor o de versión de PostgreSQL, y de vez en cuando (por ejemplo, una vez por mes) para estar seguros de que los backups sirven.

## 6. Programar el backup diario

Ningún script se programa solo: hay que activarlo a mano, con uno de estos dos métodos.

### En un servidor Linux, con cron

1. Abrí la tabla de cron del usuario que corre la app: `crontab -e`.
2. Agregá una línea para correrlo todos los días a las 3 de la mañana y guardar lo que diga en un log:

   ```
   0 3 * * * /ruta/al/repo/ops/backups/respaldar.sh >> /ruta/a/los/logs/backups.log 2>&1
   ```

3. **Para enterarte si falla:** cron manda un mail con la salida si el servidor tiene correo configurado (variable `MAILTO` al principio del crontab). Si no, conviene un servicio de monitoreo de tareas programadas, que avisa si un día no llega la señal: se agrega `&& curl -fsS <dirección del monitoreo>` al final de la línea.
4. **Para desactivarlo:** `crontab -e` y borrar la línea.

### En la Mac, con launchd

En la Mac se usa launchd en lugar de cron, porque corre la tarea al despertar si la Mac estaba dormida a esa hora.

1. Creá el archivo `~/Library/LaunchAgents/ar.greenfire.backup.plist` con este contenido, cambiando las rutas:

   ```xml
   <?xml version="1.0" encoding="UTF-8"?>
   <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
   <plist version="1.0">
   <dict>
       <key>Label</key><string>ar.greenfire.backup</string>
       <key>ProgramArguments</key>
       <array><string>/ruta/al/repo/ops/backups/respaldar.sh</string></array>
       <key>StartCalendarInterval</key>
       <dict><key>Hour</key><integer>3</integer><key>Minute</key><integer>0</integer></dict>
       <key>EnvironmentVariables</key>
       <dict><key>PATH</key><string>/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin</string></dict>
       <key>StandardOutPath</key><string>/ruta/a/los/logs/backups.log</string>
       <key>StandardErrorPath</key><string>/ruta/a/los/logs/backups.log</string>
   </dict>
   </plist>
   ```

2. Activalo: `launchctl load ~/Library/LaunchAgents/ar.greenfire.backup.plist`.
3. Para probarlo en el momento: `launchctl start ar.greenfire.backup`, y mirá el log.
4. Para desactivarlo: `launchctl unload ~/Library/LaunchAgents/ar.greenfire.backup.plist` y borrá el archivo.

- **Docker:** si la base corre en Docker (`BACKUP_PG_DOCKER`), Docker tiene que estar abierto a esa hora. Si no, el backup falla con el código 1 y lo dice en el log.
- **Permisos de macOS:** si el repositorio o `BACKUP_DIR` están en Documentos, Escritorio o Descargas, macOS puede bloquear el acceso a launchd. Conviene usar otra carpeta, o darle a bash "Acceso total al disco" en Ajustes del Sistema.

## 7. Tests de los scripts

```sh
ops/backups/tests/probar-scripts.sh
```

- No tocan la base de desarrollo ni el `.env`: levantan un PostgreSQL 16 descartable en Docker, con carpetas temporales y una clave al azar, y borran todo al terminar.
- Necesitan Docker, openssl, curl y python3.
- La copia remota se prueba con un servidor S3 de prueba en la misma máquina, que recibe el archivo y la firma del pedido. Contra Backblaze B2 de verdad todavía no se probó: falta tener la cuenta (punto 8.13 del plan).

## 8. Límites conocidos

- **Integridad del cifrado:** el cifrado (AES-256-CBC de openssl) protege la confidencialidad, pero no tiene una firma propia que detecte una modificación del archivo. Un archivo dañado o modificado se detecta igual al verificarlo o restaurarlo, porque `pg_restore` no lo puede leer.
- **Punto en el tiempo:** un backup diario puede perder hasta un día de datos. Si eso no alcanza, la alternativa es un servicio de base de datos administrado con recuperación a un momento dado (punto 8.13).
- **Hosting:** dónde corre el backup, y si conviene un servicio que ya incluya backups, depende de dónde se aloje el sistema, que todavía no está definido (punto 8.13).
