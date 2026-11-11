#!/usr/bin/env bash
# Backup de la base: pg_dump comprimido y cifrado, verificado, con retención y copia opcional a un almacenamiento
# compatible con S3. Toda la configuración sale de variables de entorno (o del .env); ver docs/backups.md.
set -euo pipefail
umask 077

source "$(dirname "${BASH_SOURCE[0]}")/comun.sh"

cargar_env
preparar_conexion
preparar_cifrado
preparar_carpeta
DIAS=${BACKUP_RETENCION_DIAS:-30}
[[ $DIAS =~ ^[0-9]+$ ]] && (( DIAS >= 1 )) \
    || fallar $SALIDA_CONFIGURACION "BACKUP_RETENCION_DIAS tiene que ser un número de días, 1 o más."

# --- Copia fuera del servidor: configuración ---

S3_VARIABLES=(BACKUP_S3_ENDPOINT BACKUP_S3_REGION BACKUP_S3_BUCKET BACKUP_S3_KEY_ID BACKUP_S3_SECRET)

# Devuelve 0 si la copia remota está configurada, 1 si no. Si está a medias o mal, falla antes de hacer el backup.
copia_remota_configurada() {
    local nombre faltan=() hay=0
    for nombre in "${S3_VARIABLES[@]}"; do
        if [[ -n ${!nombre:-} ]]; then hay=1; else faltan+=("$nombre"); fi
    done
    (( hay )) || return 1
    (( ${#faltan[@]} == 0 )) || fallar $SALIDA_CONFIGURACION \
        "La copia remota está configurada a medias. Faltan: ${faltan[*]}. Completalas o dejá todas vacías."
    if [[ ! $BACKUP_S3_ENDPOINT =~ ^https:// && ! $BACKUP_S3_ENDPOINT =~ ^http://(localhost|127\.0\.0\.1)(:[0-9]+)?/?$ ]]; then
        fallar $SALIDA_CONFIGURACION "BACKUP_S3_ENDPOINT tiene que empezar con https://."
    fi
    curl --help all 2> /dev/null | grep -q -- '--aws-sigv4' \
        || fallar $SALIDA_CONFIGURACION "La versión de curl no admite --aws-sigv4: hace falta curl 7.75 o más nuevo."
    return 0
}

# subir_copia <archivo>: lo sube con PUT firmado (AWS SigV4) y comprueba con HEAD que llegó entero.
# Las credenciales van a curl por la entrada estándar, no por la línea de comandos.
subir_copia() {
    local archivo=$1 url remoto
    url="${BACKUP_S3_ENDPOINT%/}/$BACKUP_S3_BUCKET/${BACKUP_S3_PREFIJO:-}$(basename "$archivo")"
    curl_s3() {
        printf 'user = "%s:%s"\n' "$BACKUP_S3_KEY_ID" "$BACKUP_S3_SECRET" \
            | curl --config - --silent --show-error --fail --retry 3 \
                --aws-sigv4 "aws:amz:$BACKUP_S3_REGION:s3" -H 'x-amz-content-sha256: UNSIGNED-PAYLOAD' "$@"
    }
    curl_s3 --upload-file "$archivo" --output /dev/null "$url" || return 1
    remoto=$(curl_s3 --head "$url" | tr -d '\r' | awk 'tolower($1) == "content-length:" { print $2 }')
    if [[ $remoto != "$(tamano "$archivo")" ]]; then
        log "La copia remota mide ${remoto:-?} bytes y la local $(tamano "$archivo")."
        return 1
    fi
}

COPIA_REMOTA=0
if copia_remota_configurada; then COPIA_REMOTA=1; fi

# --- Backup ---

tomar_bloqueo
trap 'rm -f -- "${PARCIAL:-}"; soltar_bloqueo' EXIT
# Dos backups en el mismo segundo tendrían el mismo nombre: el segundo espera al siguiente.
NOMBRE="$POSTGRES_DB-$(date '+%Y%m%d-%H%M%S').dump.enc"
if [[ -e $BACKUP_DIR/$NOMBRE ]]; then
    sleep 1
    NOMBRE="$POSTGRES_DB-$(date '+%Y%m%d-%H%M%S').dump.enc"
fi
DESTINO=$BACKUP_DIR/$NOMBRE
PARCIAL=$DESTINO.parcial
[[ ! -e $DESTINO ]] || fallar $SALIDA_RESPALDO "Ya existe $DESTINO."

log "Backup de la base $POSTGRES_DB en $DESTINO."
# El dump sin cifrar nunca se escribe en el disco: pasa directo de pg_dump a openssl.
if ! pg pg_dump --format=custom --compress=6 --dbname="$POSTGRES_DB" | cifrar > "$PARCIAL"; then
    fallar $SALIDA_RESPALDO "Falló pg_dump o el cifrado. No se guardó ningún backup nuevo."
fi
chmod 600 "$PARCIAL"

if ! verificar_respaldo "$PARCIAL"; then
    fallar $SALIDA_VERIFICACION "El backup no pasó la verificación y se descartó. Los anteriores no se tocaron."
fi
mv -- "$PARCIAL" "$DESTINO"
log "Backup verificado: $NOMBRE, $(tamano "$DESTINO") bytes, $VERIFICADO_TABLAS tablas con datos."

aplicar_retencion "$BACKUP_DIR" "$POSTGRES_DB" "$DIAS" \
    || fallar $SALIDA_RETENCION "Falló la retención. El backup nuevo quedó guardado en $DESTINO."

if (( COPIA_REMOTA )); then
    log "Copia remota: subiendo a $BACKUP_S3_ENDPOINT, bucket $BACKUP_S3_BUCKET."
    subir_copia "$DESTINO" \
        || fallar $SALIDA_COPIA_REMOTA "Falló la copia remota. El backup quedó guardado en el servidor, en $DESTINO."
    log "Copia remota lista: ${BACKUP_S3_PREFIJO:-}$NOMBRE."
else
    aviso "La copia remota no está configurada (variables BACKUP_S3_*): el backup quedó solo en este servidor."
fi

log "Listo."
