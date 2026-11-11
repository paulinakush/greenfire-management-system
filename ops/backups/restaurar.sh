#!/usr/bin/env bash
# Restaura un backup en una base vacía, distinta de la de trabajo (POSTGRES_DB). Pide confirmación escribiendo el
# nombre de la base de destino. Uso: ops/backups/restaurar.sh <archivo.dump.enc> <base_destino>
set -euo pipefail
umask 077

source "$(dirname "${BASH_SOURCE[0]}")/comun.sh"

(( $# == 2 )) || fallar $SALIDA_CONFIGURACION "Uso: $0 <archivo.dump.enc> <base_destino>"
ARCHIVO=$1
DESTINO=$2

cargar_env
preparar_conexion
preparar_cifrado

[[ -f $ARCHIVO ]] || fallar $SALIDA_CONFIGURACION "No existe el archivo $ARCHIVO."
[[ $DESTINO =~ ^[a-z_][a-z0-9_]{0,62}$ ]] \
    || fallar $SALIDA_CONFIGURACION "El nombre de la base de destino solo lleva minúsculas, números y guion bajo."
case $DESTINO in
    "$POSTGRES_DB")
        fallar $SALIDA_CONFIGURACION "La base $DESTINO es la de trabajo (POSTGRES_DB): nunca se restaura sobre ella." ;;
    postgres | template0 | template1)
        fallar $SALIDA_CONFIGURACION "La base $DESTINO es del sistema: elegí otro nombre." ;;
esac

log "Verificando el backup $ARCHIVO."
verificar_respaldo "$ARCHIVO" || fallar $SALIDA_VERIFICACION "El backup no se puede leer: no se restauró nada."
log "El backup se lee bien: $VERIFICADO_TABLAS tablas con datos."

# El nombre ya se validó con la expresión de arriba, así que se puede poner en las consultas.
EXISTE=$(psql_valor postgres "select count(*) from pg_database where datname = '$DESTINO'") \
    || fallar $SALIDA_CONFIGURACION "No se pudo conectar a PostgreSQL."
if (( EXISTE )); then
    EN_USO=$(psql_valor postgres "select count(*) from pg_stat_activity where datname = '$DESTINO'")
    (( EN_USO == 0 )) || fallar $SALIDA_CONFIGURACION \
        "La base $DESTINO tiene $EN_USO conexiones abiertas: está en uso y no se restaura sobre ella."
    OBJETOS=$(psql_valor "$DESTINO" "select count(*) from pg_class c join pg_namespace n on n.oid = c.relnamespace
        where n.nspname not in ('pg_catalog', 'information_schema') and n.nspname not like 'pg\_toast%'")
    (( OBJETOS == 0 )) || fallar $SALIDA_CONFIGURACION \
        "La base $DESTINO no está vacía ($OBJETOS objetos): se restaura solo en una base vacía o nueva."
    ESTADO="existe y está vacía"
else
    ESTADO="no existe: se va a crear"
fi

if [[ -n ${BACKUP_PG_DOCKER:-} ]]; then SERVIDOR="contenedor $BACKUP_PG_DOCKER"; else SERVIDOR="$PGHOST:$PGPORT"; fi
echo "Se va a restaurar:" >&2
echo "  Backup:         $ARCHIVO" >&2
echo "  Servidor:       $SERVIDOR" >&2
echo "  Base destino:   $DESTINO ($ESTADO)" >&2
echo "  Base de trabajo, que no se toca: $POSTGRES_DB" >&2
printf 'Para confirmar, escribí el nombre de la base de destino: ' >&2
RESPUESTA=""
read -r RESPUESTA || true
[[ $RESPUESTA == "$DESTINO" ]] || fallar $SALIDA_CANCELADO "No coincide con el nombre de la base de destino: no se restauró nada."

if (( ! EXISTE )); then
    psql_valor postgres "create database $DESTINO" > /dev/null \
        || fallar $SALIDA_RESTAURACION "No se pudo crear la base $DESTINO."
    log "Base $DESTINO creada."
fi

log "Restaurando en $DESTINO."
# En una sola transacción: si algo falla, la base queda vacía como estaba.
if ! descifrar "$ARCHIVO" | pg pg_restore --dbname="$DESTINO" --no-owner --no-privileges --exit-on-error \
        --single-transaction; then
    fallar $SALIDA_RESTAURACION "Falló la restauración. La base $DESTINO quedó vacía; revisá el mensaje de arriba."
fi

TABLAS=$(psql_valor "$DESTINO" "select count(*) from information_schema.tables
    where table_schema = 'public' and table_type = 'BASE TABLE'")
VERSION=$(psql_valor "$DESTINO" "select coalesce(max(version::int), 0) from flyway_schema_history where success" \
    2> /dev/null || echo "sin historial de Flyway")
log "Restauración lista: $TABLAS tablas en $DESTINO; versión de Flyway: $VERSION."
