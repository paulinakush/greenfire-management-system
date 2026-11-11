#!/usr/bin/env bash
# Tests de los scripts de backup. No tocan la base de desarrollo ni el .env: levantan un PostgreSQL 16 descartable
# en Docker, usan carpetas temporales y una clave al azar, y borran todo al terminar.
# Uso: ops/backups/tests/probar-scripts.sh   (necesita Docker, openssl, curl y python3)
set -uo pipefail

AQUI=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
SCRIPTS=$(dirname "$AQUI")
TMP=$(mktemp -d)
CONTENEDOR=greenfire-prueba-backups-$$
STUB_PID=""
OK=0
FALLAS=0

limpiar() {
    [[ -z $STUB_PID ]] || kill "$STUB_PID" 2> /dev/null
    docker rm -f "$CONTENEDOR" > /dev/null 2>&1
    rm -rf "$TMP"
}
trap limpiar EXIT

ok() { OK=$((OK + 1)); echo "  ok    $*"; }
falla() {
    FALLAS=$((FALLAS + 1))
    echo "  FALLA $*"
    [[ ! -s $TMP/salida ]] || sed 's/^/          | /' "$TMP/salida" | tail -n 4
}
# comprobar <descripción> <comando...>
comprobar() {
    local descripcion=$1
    shift
    if "$@"; then ok "$descripcion"; else falla "$descripcion"; fi
}
aleatorio() { openssl rand -hex 16; }

# Entorno de prueba: nada sale del .env real.
: > "$TMP/vacio.env"
export GREENFIRE_ENV_FILE=$TMP/vacio.env
export POSTGRES_DB=trabajo POSTGRES_USER=prueba POSTGRES_PASSWORD=$(aleatorio)
export BACKUP_PG_DOCKER=$CONTENEDOR
export BACKUP_CLAVE_CIFRADO=$(aleatorio)$(aleatorio)
export BACKUP_DIR=$TMP/backups
export BACKUP_RETENCION_DIAS=30
unset BACKUP_S3_ENDPOINT BACKUP_S3_REGION BACKUP_S3_BUCKET BACKUP_S3_PREFIJO BACKUP_S3_KEY_ID BACKUP_S3_SECRET

echo "Levantando PostgreSQL 16 descartable ($CONTENEDOR)..."
docker run -d --rm --name "$CONTENEDOR" -e POSTGRES_DB -e POSTGRES_USER -e POSTGRES_PASSWORD \
    postgres:16-alpine > /dev/null || { echo "No se pudo levantar el contenedor."; exit 1; }
for _ in $(seq 60); do
    docker exec "$CONTENEDOR" pg_isready -U prueba -d trabajo -h localhost > /dev/null 2>&1 && break
    sleep 1
done
sql() { docker exec -i -e PGPASSWORD="$POSTGRES_PASSWORD" "$CONTENEDOR" psql -X -q -t -A -v ON_ERROR_STOP=1 -h localhost \
    -U prueba -d "$1" -c "$2"; }
sql trabajo "create table articulos (id int primary key, nombre text);
    insert into articulos select g, 'Articulo ' || g from generate_series(1, 250) g;
    create table flyway_schema_history (version varchar(50), success boolean);
    insert into flyway_schema_history values ('1', true), ('2', true);" \
    || { echo "No se pudieron cargar los datos de prueba."; exit 1; }

respaldar() { "$SCRIPTS/respaldar.sh" > "$TMP/salida" 2>&1; }
ultimo() { find "$BACKUP_DIR" -maxdepth 1 -name 'trabajo-*.dump.enc' | sort | tail -n 1; }
salida_dice() { grep -q -- "$1" "$TMP/salida"; }
codigo() { "$@" > "$TMP/salida" 2>&1; echo $?; }

echo "Configuración"
comprobar "sin clave de cifrado falla con código 1" test "$(BACKUP_CLAVE_CIFRADO='' codigo "$SCRIPTS/respaldar.sh")" = 1
comprobar "  y explica qué falta" salida_dice "Falta la variable BACKUP_CLAVE_CIFRADO"
comprobar "una clave corta falla" test "$(BACKUP_CLAVE_CIFRADO=corta codigo "$SCRIPTS/respaldar.sh")" = 1
comprobar "una carpeta dentro del repositorio falla" \
    test "$(BACKUP_DIR="$SCRIPTS/../../backups-no" codigo "$SCRIPTS/respaldar.sh")" = 1
comprobar "  y no la crea" test ! -e "$SCRIPTS/../../backups-no"
comprobar "una carpeta relativa falla" test "$(BACKUP_DIR=backups codigo "$SCRIPTS/respaldar.sh")" = 1
comprobar "la copia remota a medias falla antes del backup" \
    test "$(BACKUP_S3_ENDPOINT=https://s3.example.com codigo "$SCRIPTS/respaldar.sh")" = 1
comprobar "  y nombra las variables que faltan" salida_dice "Faltan: BACKUP_S3_REGION"
comprobar "  sin dejar backups" test -z "$(ultimo 2> /dev/null)"

echo "Backup"
comprobar "termina con código 0" respaldar
ARCHIVO=$(ultimo)
comprobar "deja un archivo con la fecha en el nombre" \
    test -n "$(basename "$ARCHIVO" | grep -E '^trabajo-[0-9]{8}-[0-9]{6}\.dump\.enc$')"
source "$SCRIPTS/comun.sh"
comprobar "el archivo tiene permisos 600" test "$(permisos "$ARCHIVO")" = 600
comprobar "la carpeta tiene permisos 700" test "$(permisos "$BACKUP_DIR")" = 700
comprobar "está cifrado (no empieza con la firma de pg_dump)" test "$(head -c 5 "$ARCHIVO")" != PGDMP
comprobar "no contiene el texto de los datos" test -z "$(grep -a 'Articulo' "$ARCHIVO")"
comprobar "informa la verificación" salida_dice "Backup verificado"
comprobar "avisa que la copia remota no está configurada, sin fallar" salida_dice "La copia remota no está configurada"
comprobar "no deja archivos parciales ni el bloqueo" \
    test -z "$(find "$BACKUP_DIR" -name '*.parcial' -o -name '.respaldo-en-curso')"
comprobar "con la clave se descifra y pg_restore lo lee" \
    sh -c "openssl enc -aes-256-cbc -md sha256 -pbkdf2 -iter 600000 -d -pass env:BACKUP_CLAVE_CIFRADO -in '$ARCHIVO' | docker exec -i $CONTENEDOR pg_restore --list | grep -q 'TABLE DATA public articulos'"
comprobar "con otra clave no se descifra" \
    sh -c "! BACKUP_CLAVE_CIFRADO=otra-clave-de-veinte-caracteres openssl enc -aes-256-cbc -md sha256 -pbkdf2 -iter 600000 -d -pass env:BACKUP_CLAVE_CIFRADO -in '$ARCHIVO' 2> /dev/null | docker exec -i $CONTENEDOR pg_restore --list > /dev/null 2>&1"

echo "Fallas del backup"
ANTES=$(find "$BACKUP_DIR" -type f | wc -l)
comprobar "si pg_dump falla (una base que no existe), termina con código 2" \
    test "$(POSTGRES_DB=noexiste codigo "$SCRIPTS/respaldar.sh")" = 2
comprobar "  y lo explica" salida_dice "Falló pg_dump"
comprobar "  sin dejar archivos nuevos" test "$(find "$BACKUP_DIR" -type f | wc -l)" = "$ANTES"
sql postgres "create database vacia" > /dev/null
comprobar "una base sin datos no pasa la verificación (código 3)" \
    test "$(POSTGRES_DB=vacia codigo "$SCRIPTS/respaldar.sh")" = 3
comprobar "  y se descarta" test -z "$(find "$BACKUP_DIR" -name 'vacia-*')"
mkdir "$BACKUP_DIR/.respaldo-en-curso"
comprobar "con otro backup en curso no arranca" test "$(codigo "$SCRIPTS/respaldar.sh")" = 1
rmdir "$BACKUP_DIR/.respaldo-en-curso"
head -c 1000 "$ARCHIVO" > "$TMP/cortado.dump.enc"
comprobar "un archivo cortado no pasa la verificación" sh -c "! (source '$SCRIPTS/comun.sh'; verificar_respaldo '$TMP/cortado.dump.enc') 2> /dev/null"
: > "$TMP/vacio.dump.enc"
comprobar "un archivo vacío no pasa la verificación" sh -c "! (source '$SCRIPTS/comun.sh'; verificar_respaldo '$TMP/vacio.dump.enc') 2> /dev/null"

echo "Retención"
R=$TMP/retencion
mkdir -p "$R"
for f in trabajo-20200101-030000 trabajo-20200102-030000 otra-20200101-030000; do
    touch -t 202001010300 "$R/$f.dump.enc"
done
touch "$R/trabajo-29990101-030000.dump.enc"
touch -t 202001010300 "$R/notas.txt" "$R/trabajo-20200101-030000.dump.enc.parcial"
(source "$SCRIPTS/comun.sh"; aplicar_retencion "$R" trabajo 30) 2> /dev/null
comprobar "borra los de más de 30 días" test ! -e "$R/trabajo-20200101-030000.dump.enc" -a ! -e "$R/trabajo-20200102-030000.dump.enc"
comprobar "conserva los recientes" test -e "$R/trabajo-29990101-030000.dump.enc"
comprobar "no toca los de otra base ni otros archivos" \
    test -e "$R/otra-20200101-030000.dump.enc" -a -e "$R/notas.txt" -a -e "$R/trabajo-20200101-030000.dump.enc.parcial"
R2=$TMP/retencion-solo-viejos
mkdir -p "$R2"
touch -t 202001010300 "$R2/trabajo-20200101-030000.dump.enc" "$R2/trabajo-20200105-030000.dump.enc"
(source "$SCRIPTS/comun.sh"; aplicar_retencion "$R2" trabajo 30) 2> /dev/null
comprobar "si todos son viejos, conserva el más reciente" \
    test -e "$R2/trabajo-20200105-030000.dump.enc" -a ! -e "$R2/trabajo-20200101-030000.dump.enc"
VIEJO=$BACKUP_DIR/trabajo-20200101-030000.dump.enc
cp "$ARCHIVO" "$VIEJO"
touch -t 202001010300 "$VIEJO"
respaldar
comprobar "el backup aplica la retención al terminar" test ! -e "$VIEJO" -a -e "$ARCHIVO"
comprobar "un valor de retención inválido falla" test "$(BACKUP_RETENCION_DIAS=0 codigo "$SCRIPTS/respaldar.sh")" = 1

echo "Copia remota (servidor S3 de prueba en esta máquina)"
cat > "$TMP/s3.py" << 'EOF'
import http.server, os, sys
CARPETA = sys.argv[1]
class S3(http.server.BaseHTTPRequestHandler):
    def log_message(self, *a): pass
    def ruta(self): return os.path.join(CARPETA, self.path.strip("/").replace("/", "__"))
    def do_PUT(self):
        cuerpo = self.rfile.read(int(self.headers["Content-Length"]))
        open(self.ruta(), "wb").write(cuerpo)
        open(self.ruta() + ".auth", "w").write(self.headers.get("Authorization", ""))
        self.send_response(200); self.send_header("Content-Length", "0"); self.end_headers()
    def do_HEAD(self):
        if not os.path.exists(self.ruta()): self.send_response(404); self.end_headers(); return
        self.send_response(200); self.send_header("Content-Length", str(os.path.getsize(self.ruta()))); self.end_headers()
http.server.HTTPServer(("127.0.0.1", int(sys.argv[2])), S3).serve_forever()
EOF
mkdir -p "$TMP/s3"
PUERTO=$((20000 + RANDOM % 20000))
python3 -I "$TMP/s3.py" "$TMP/s3" "$PUERTO" & STUB_PID=$!
sleep 1
s3() { BACKUP_S3_ENDPOINT=http://127.0.0.1:$PUERTO BACKUP_S3_REGION=us-west-004 BACKUP_S3_BUCKET=respaldos \
    BACKUP_S3_PREFIJO=diarios/ BACKUP_S3_KEY_ID=clave-de-prueba BACKUP_S3_SECRET=$(aleatorio) "$@"; }
comprobar "con la copia configurada termina con código 0" test "$(s3 codigo "$SCRIPTS/respaldar.sh")" = 0
ARCHIVO=$(ultimo)
REMOTO=$TMP/s3/respaldos__diarios__$(basename "$ARCHIVO")
comprobar "sube el archivo entero al bucket y prefijo configurados" cmp -s "$ARCHIVO" "$REMOTO"
comprobar "firma el pedido con AWS SigV4 y la región configurada" \
    grep -q 'AWS4-HMAC-SHA256 Credential=clave-de-prueba/[0-9]*/us-west-004/s3/aws4_request' "$REMOTO.auth"
comprobar "informa la copia" salida_dice "Copia remota lista"
kill "$STUB_PID"; wait "$STUB_PID" 2> /dev/null; STUB_PID=""
comprobar "si la subida falla, termina con código 4" test "$(s3 codigo "$SCRIPTS/respaldar.sh")" = 4
comprobar "  y el backup local queda guardado" salida_dice "quedó guardado en el servidor"
comprobar "un endpoint http fuera de esta máquina falla" \
    test "$(s3 env BACKUP_S3_ENDPOINT=http://s3.example.com "$SCRIPTS/respaldar.sh" > "$TMP/salida" 2>&1; echo $?)" = 1

echo "Restauración"
ARCHIVO=$(ultimo)
restaurar() { printf '%s\n' "$2" | "$SCRIPTS/restaurar.sh" "$ARCHIVO" "$1" > "$TMP/salida" 2>&1; echo $?; }
comprobar "sobre la base de trabajo, falla aunque se confirme" test "$(restaurar trabajo trabajo)" = 1
comprobar "  y lo explica" salida_dice "es la de trabajo"
comprobar "sobre una base del sistema, falla" test "$(restaurar postgres postgres)" = 1
comprobar "con un nombre inválido, falla" test "$(restaurar 'x; drop' 'x; drop')" = 1
sql postgres "create database con_datos" > /dev/null
sql con_datos "create table algo (id int)" > /dev/null
comprobar "sobre una base con tablas, falla" test "$(restaurar con_datos con_datos)" = 1
comprobar "si la confirmación no coincide, cancela (código 7)" test "$(restaurar restaurada otra)" = 7
comprobar "  sin crear la base" test "$(sql postgres "select count(*) from pg_database where datname = 'restaurada'")" = 0
comprobar "sin confirmación (entrada vacía), cancela" \
    test "$("$SCRIPTS/restaurar.sh" "$ARCHIVO" restaurada < /dev/null > "$TMP/salida" 2>&1; echo $?)" = 7
comprobar "con otra clave, no restaura nada (código 3)" \
    test "$(BACKUP_CLAVE_CIFRADO=otra-clave-de-veinte-caracteres restaurar restaurada restaurada)" = 3
comprobar "confirmando, restaura en una base nueva" test "$(restaurar restaurada restaurada)" = 0
comprobar "  con las mismas filas" test "$(sql restaurada 'select count(*) from articulos')" = 250
comprobar "  e informa la versión de Flyway" salida_dice "versión de Flyway: 2"
comprobar "  sin tocar la base de trabajo" test "$(sql trabajo 'select count(*) from articulos')" = 250
sql postgres "create database vacia_existente" > /dev/null
comprobar "restaura en una base existente y vacía" test "$(restaurar vacia_existente vacia_existente)" = 0
comprobar "otra vez sobre la misma base (ya con datos), falla" test "$(restaurar restaurada restaurada)" = 1

echo
echo "$OK pasan, $FALLAS fallan."
(( FALLAS == 0 ))
