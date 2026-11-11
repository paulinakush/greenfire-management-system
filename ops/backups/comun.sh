# Funciones comunes de los scripts de backup. Se carga con `source`; no se ejecuta sola.
# Compatible con bash 3.2 (el de macOS) y con Linux.

RAIZ_REPO=$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd -P)

# Códigos de salida, documentados en docs/backups.md.
SALIDA_CONFIGURACION=1
SALIDA_RESPALDO=2
SALIDA_VERIFICACION=3
SALIDA_COPIA_REMOTA=4
SALIDA_RETENCION=5
SALIDA_RESTAURACION=6
SALIDA_CANCELADO=7

log() { printf '%s  %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$*" >&2; }
aviso() { log "AVISO: $*"; }

# fallar <código> <mensaje>: termina el script con un error claro.
fallar() {
    local codigo=$1
    shift
    log "ERROR: $*"
    exit "$codigo"
}

# Lee el .env de la raíz del repo (o el de GREENFIRE_ENV_FILE). Una variable que ya está en el entorno, aunque esté
# vacía, no se pisa. No se usa `source`, así el archivo no puede ejecutar nada.
cargar_env() {
    local archivo linea clave valor
    if [[ -n ${GREENFIRE_ENV_FILE:-} ]]; then
        archivo=$GREENFIRE_ENV_FILE
        [[ -f $archivo ]] || fallar $SALIDA_CONFIGURACION "No existe el archivo de configuración GREENFIRE_ENV_FILE ($archivo)."
    else
        archivo=$RAIZ_REPO/.env
        [[ -f $archivo ]] || return 0
    fi
    while IFS= read -r linea || [[ -n $linea ]]; do
        linea=${linea%$'\r'}
        [[ $linea =~ ^([A-Za-z_][A-Za-z0-9_]*)=(.*)$ ]] || continue
        clave=${BASH_REMATCH[1]}
        valor=${BASH_REMATCH[2]}
        if [[ -z ${!clave+x} ]]; then
            export "$clave=$valor"
        fi
    done < "$archivo"
}

exigir_variable() {
    local nombre
    for nombre in "$@"; do
        [[ -n ${!nombre:-} ]] || fallar $SALIDA_CONFIGURACION "Falta la variable $nombre (ver .env.example y docs/backups.md)."
    done
}

# Conexión a PostgreSQL. Con BACKUP_PG_DOCKER, las herramientas (pg_dump, pg_restore, psql) corren dentro de ese
# contenedor; si no, tienen que estar instaladas en el servidor, en la misma versión mayor que la base o más nueva.
preparar_conexion() {
    exigir_variable POSTGRES_DB POSTGRES_USER POSTGRES_PASSWORD
    export PGUSER=$POSTGRES_USER PGPASSWORD=$POSTGRES_PASSWORD
    if [[ -n ${BACKUP_PG_DOCKER:-} ]]; then
        command -v docker > /dev/null || fallar $SALIDA_CONFIGURACION "BACKUP_PG_DOCKER está configurada, pero no se encontró docker."
        docker exec "$BACKUP_PG_DOCKER" true 2> /dev/null \
            || fallar $SALIDA_CONFIGURACION "No se puede usar el contenedor $BACKUP_PG_DOCKER (BACKUP_PG_DOCKER): ¿está corriendo?"
    else
        export PGHOST=${DB_HOST:-localhost} PGPORT=${DB_PORT:-5432}
        local herramienta
        for herramienta in pg_dump pg_restore psql; do
            command -v "$herramienta" > /dev/null || fallar $SALIDA_CONFIGURACION \
                "No se encontró $herramienta. Instalá el cliente de PostgreSQL 16 o configurá BACKUP_PG_DOCKER."
        done
    fi
}

# pg <herramienta> [argumentos]: corre una herramienta de PostgreSQL, en el servidor o en el contenedor.
pg() {
    if [[ -n ${BACKUP_PG_DOCKER:-} ]]; then
        docker exec -i -e PGUSER -e PGPASSWORD -e PGHOST=localhost -e PGPORT=5432 "$BACKUP_PG_DOCKER" "$@"
    else
        "$@"
    fi
}

# psql_valor <base> <consulta>: el resultado de una consulta, sin encabezados. No lee la entrada estándar, que
# queda para la confirmación de restaurar.sh.
psql_valor() {
    pg psql -X -q -v ON_ERROR_STOP=1 -t -A -d "$1" -c "$2" < /dev/null
}

# --- Cifrado ---
# AES-256 con una clave derivada de BACKUP_CLAVE_CIFRADO (PBKDF2). La clave viaja por el entorno, nunca por la
# línea de comandos, así no aparece en la lista de procesos.

CIFRADO_ARGS=(enc -aes-256-cbc -md sha256 -pbkdf2 -iter 600000 -pass env:BACKUP_CLAVE_CIFRADO)

preparar_cifrado() {
    exigir_variable BACKUP_CLAVE_CIFRADO
    (( ${#BACKUP_CLAVE_CIFRADO} >= 20 )) \
        || fallar $SALIDA_CONFIGURACION "BACKUP_CLAVE_CIFRADO tiene que tener al menos 20 caracteres."
    export BACKUP_CLAVE_CIFRADO
    command -v openssl > /dev/null || fallar $SALIDA_CONFIGURACION "No se encontró openssl."
    printf 'x' | openssl enc -aes-256-cbc -md sha256 -pbkdf2 -iter 1 -pass pass:x > /dev/null 2>&1 \
        || fallar $SALIDA_CONFIGURACION "La versión de openssl no admite -pbkdf2: hace falta OpenSSL 1.1.1 o más nuevo."
}

cifrar() { openssl "${CIFRADO_ARGS[@]}" -salt; }
descifrar() { openssl "${CIFRADO_ARGS[@]}" -d -in "$1"; }

# --- Archivos ---

# Ruta absoluta sin enlaces simbólicos, aunque la carpeta todavía no exista.
ruta_fisica() {
    local ruta=$1 resto=""
    while [[ ! -d $ruta ]]; do
        resto=/$(basename "$ruta")$resto
        ruta=$(dirname "$ruta")
    done
    printf '%s%s\n' "$(cd "$ruta" && pwd -P)" "$resto"
}

permisos() {
    if stat -c %a "$1" > /dev/null 2>&1; then stat -c %a "$1"; else stat -f %Lp "$1"; fi
}

tamano() { wc -c < "$1" | tr -d ' '; }

# Carpeta de backups: absoluta, fuera del repo y solo para el usuario (700).
preparar_carpeta() {
    exigir_variable BACKUP_DIR
    [[ $BACKUP_DIR == /* ]] || fallar $SALIDA_CONFIGURACION "BACKUP_DIR tiene que ser una ruta absoluta."
    local fisica
    fisica=$(ruta_fisica "$BACKUP_DIR")
    if [[ $fisica == "$RAIZ_REPO" || $fisica == "$RAIZ_REPO"/* ]]; then
        fallar $SALIDA_CONFIGURACION "BACKUP_DIR está dentro del repositorio: los backups van en una carpeta aparte."
    fi
    mkdir -p "$BACKUP_DIR" || fallar $SALIDA_CONFIGURACION "No se pudo crear la carpeta $BACKUP_DIR."
    chmod 700 "$BACKUP_DIR" || fallar $SALIDA_CONFIGURACION "No se pudieron ajustar los permisos de $BACKUP_DIR."
    [[ -w $BACKUP_DIR ]] || fallar $SALIDA_CONFIGURACION "No se puede escribir en $BACKUP_DIR."
}

# --- Verificación ---

# verificar_respaldo <archivo>: el archivo no está vacío, se descifra y pg_restore puede leer su índice con datos.
# Si está bien, deja en VERIFICADO_TABLAS la cantidad de tablas con datos del backup.
verificar_respaldo() {
    local archivo=$1 indice
    [[ -s $archivo ]] || { log "El archivo $archivo está vacío."; return 1; }
    indice=$(descifrar "$archivo" | pg pg_restore --list) \
        || { log "No se pudo descifrar o leer el backup $archivo (¿clave equivocada o archivo dañado?)."; return 1; }
    VERIFICADO_TABLAS=$(printf '%s\n' "$indice" | grep -c ' TABLE DATA ' || true)
    (( VERIFICADO_TABLAS > 0 )) || { log "El backup $archivo no tiene datos de ninguna tabla."; return 1; }
}

# --- Retención ---

# aplicar_retencion <carpeta> <prefijo> <días>: borra los backups de esa base con más de <días> días, salvo el más
# reciente, que no se borra nunca. Solo toca archivos con el nombre de los backups (<prefijo>-AAAAMMDD-HHMMSS.dump.enc).
aplicar_retencion() {
    local carpeta=$1 prefijo=$2 dias=$3 patron mas_reciente archivo borrados=0
    [[ $dias =~ ^[0-9]+$ ]] && (( dias >= 1 )) \
        || fallar $SALIDA_CONFIGURACION "BACKUP_RETENCION_DIAS tiene que ser un número de días, 1 o más."
    patron="$prefijo-[0-9][0-9][0-9][0-9][0-9][0-9][0-9][0-9]-[0-9][0-9][0-9][0-9][0-9][0-9].dump.enc"
    # El nombre lleva la fecha, así que el último en orden alfabético es el más reciente.
    mas_reciente=$(find "$carpeta" -maxdepth 1 -type f -name "$patron" | LC_ALL=C sort | tail -n 1)
    [[ -n $mas_reciente ]] || return 0
    while IFS= read -r archivo; do
        [[ -n $archivo && $archivo != "$mas_reciente" ]] || continue
        rm -f -- "$archivo" || return 1
        log "Retención: se borró $(basename "$archivo"), de más de $dias días."
        borrados=$((borrados + 1))
    done < <(find "$carpeta" -maxdepth 1 -type f -name "$patron" -mmin +$((dias * 1440)))
    log "Retención: $borrados backups borrados; se conservan los de los últimos $dias días."
}

# --- Bloqueo ---

# Evita dos backups a la vez en la misma carpeta. Si un corte dejó el bloqueo, se borra a mano (ver docs/backups.md).
tomar_bloqueo() {
    BLOQUEO=$BACKUP_DIR/.respaldo-en-curso
    mkdir "$BLOQUEO" 2> /dev/null \
        || fallar $SALIDA_CONFIGURACION "Ya hay un backup en curso ($BLOQUEO). Si no es así, borrá esa carpeta."
}

soltar_bloqueo() { [[ -z ${BLOQUEO:-} ]] || rmdir "$BLOQUEO" 2> /dev/null || true; }
