#!/usr/bin/env bash
# Prueba de punta a punta: backup de la base de trabajo, restauración en una base temporal, la app levantada contra
# ella y comparación de la cantidad de filas de cada tabla. La base de trabajo solo se lee.
# Usa una carpeta temporal y una clave al azar (no la de los backups reales) y no sube nada a la copia remota.
# Al terminar borra la base temporal, el backup de prueba y la app levantada.
# Uso: ops/backups/probar-restauracion.sh   (PRUEBA_PUERTO elige el puerto de la app, 8081 por defecto)
set -euo pipefail
umask 077

AQUI=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)
source "$AQUI/comun.sh"

cargar_env
TMP=$(mktemp -d)
export BACKUP_DIR=$TMP/backups
export BACKUP_CLAVE_CIFRADO=$(openssl rand -hex 32)
export BACKUP_S3_ENDPOINT= BACKUP_S3_REGION= BACKUP_S3_BUCKET= BACKUP_S3_KEY_ID= BACKUP_S3_SECRET=
preparar_conexion
PRUEBA=${POSTGRES_DB}_prueba_restauracion
PUERTO=${PRUEBA_PUERTO:-8081}
APP_PID=""
BASE_CREADA=0

limpiar() {
    if [[ -n $APP_PID ]]; then
        kill "$APP_PID" 2> /dev/null || true
        wait "$APP_PID" 2> /dev/null || true
    fi
    if (( BASE_CREADA )); then
        psql_valor postgres "drop database if exists $PRUEBA" > /dev/null 2>&1 \
            && log "Base temporal $PRUEBA borrada." || aviso "No se pudo borrar la base temporal $PRUEBA."
    fi
    rm -rf "$TMP"
}
trap limpiar EXIT

# filas <base>: "tabla cantidad" de cada tabla del esquema public, contando las filas una por una.
filas() {
    psql_valor "$1" "select table_name || ' ' || (xpath('/row/c/text()', query_to_xml(
            format('select count(*) as c from %I.%I', table_schema, table_name), false, true, '')))[1]::text
        from information_schema.tables where table_schema = 'public' and table_type = 'BASE TABLE' order by 1"
}

(( $(psql_valor postgres "select count(*) from pg_database where datname = '$PRUEBA'") == 0 )) \
    || fallar $SALIDA_CONFIGURACION "Ya existe la base $PRUEBA (¿quedó de una prueba anterior?). Borrala a mano."

log "1. Backup de $POSTGRES_DB."
filas "$POSTGRES_DB" > "$TMP/original"
"$AQUI/respaldar.sh"
ARCHIVO=$(find "$BACKUP_DIR" -maxdepth 1 -name "$POSTGRES_DB-*.dump.enc" | sort | tail -n 1)

log "2. Restauración en $PRUEBA."
BASE_CREADA=1
printf '%s\n' "$PRUEBA" | "$AQUI/restaurar.sh" "$ARCHIVO" "$PRUEBA"
filas "$PRUEBA" > "$TMP/restaurada"

log "3. Comparación de filas (la base original se contó justo antes del backup)."
DIFERENCIAS=0
while read -r tabla original; do
    restaurada=$(awk -v t="$tabla" '$1 == t { print $2 }' "$TMP/restaurada")
    if [[ $restaurada == "$original" ]]; then marca="igual"; else marca="DISTINTA"; DIFERENCIAS=$((DIFERENCIAS + 1)); fi
    printf '   %-32s original %8s   restaurada %8s   %s\n' "$tabla" "$original" "${restaurada:-falta}" "$marca" >&2
done < "$TMP/original"
TABLAS=$(wc -l < "$TMP/original" | tr -d ' ')
(( $(wc -l < "$TMP/restaurada") == TABLAS )) || DIFERENCIAS=$((DIFERENCIAS + 1))
(( DIFERENCIAS == 0 )) || fallar $SALIDA_RESTAURACION "$DIFERENCIAS tablas no coinciden."
log "Las $TABLAS tablas tienen las mismas filas."

log "4. La app contra $PRUEBA, en el puerto $PUERTO."
(cd "$RAIZ_REPO/backend" && ./mvnw -q -DskipTests package) > "$TMP/build.log" 2>&1 \
    || { cat "$TMP/build.log" >&2; fallar $SALIDA_RESTAURACION "No se pudo compilar la app."; }
JAR=$(find "$RAIZ_REPO/backend/target" -maxdepth 1 -name '*.jar' ! -name '*.original' | head -n 1)
(cd "$RAIZ_REPO/backend" && POSTGRES_DB=$PRUEBA SERVER_PORT=$PUERTO exec java -jar "$JAR") > "$TMP/app.log" 2>&1 &
APP_PID=$!
API=http://localhost:$PUERTO
for _ in $(seq 120); do
    curl -s -o /dev/null "$API/api/auth/csrf" && break
    kill -0 "$APP_PID" 2> /dev/null || { tail -n 30 "$TMP/app.log" >&2; fallar $SALIDA_RESTAURACION "La app no arrancó."; }
    sleep 1
done
grep -q 'Started ' "$TMP/app.log" || { tail -n 30 "$TMP/app.log" >&2; fallar $SALIDA_RESTAURACION "La app no arrancó."; }
log "   $(grep -o 'Started [A-Za-z]* in [0-9.]* seconds' "$TMP/app.log")."
grep -o 'Schema "public" is up to date[^"]*' "$TMP/app.log" | sed 's/^/   Flyway: /' >&2 \
    || fallar $SALIDA_RESTAURACION "Flyway no validó el esquema restaurado."

LOCALES=$(curl -s -f "$API/api/publico/locales" | python3 -c 'import json, sys; print(len(json.load(sys.stdin)))')
ESPERADOS=$(psql_valor "$PRUEBA" "select count(*) from ambitos where tipo = 'local' and activo and visible_web")
log "   GET /api/publico/locales: $LOCALES locales (en la base restaurada hay $ESPERADOS visibles)."
[[ $LOCALES == "$ESPERADOS" ]] || fallar $SALIDA_RESTAURACION "La app no devuelve los locales restaurados."

if [[ -n ${ADMIN_USUARIO:-} && -n ${ADMIN_PASSWORD:-} ]]; then
    JAR_COOKIES=$TMP/cookies
    curl -s -c "$JAR_COOKIES" -b "$JAR_COOKIES" -o /dev/null "$API/api/auth/csrf"
    XSRF=$(awk '$6 == "XSRF-TOKEN" { print $7 }' "$JAR_COOKIES")
    # La contraseña va por la entrada estándar, no por la línea de comandos.
    ESTADO=$(python3 -c 'import json, os; print(json.dumps({"usuario": os.environ["ADMIN_USUARIO"],
        "password": os.environ["ADMIN_PASSWORD"]}))' \
        | curl -s -c "$JAR_COOKIES" -b "$JAR_COOKIES" -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' \
            -H "X-XSRF-TOKEN: $XSRF" -d @- "$API/api/auth/login")
    [[ $ESTADO == 200 ]] || fallar $SALIDA_RESTAURACION "El administrador técnico no pudo entrar (HTTP $ESTADO)."
    AMBITOS=$(curl -s -f -b "$JAR_COOKIES" "$API/api/admin/ambitos" | python3 -c 'import json, sys; print(len(json.load(sys.stdin)))')
    log "   El administrador técnico entra con su contraseña de siempre y ve $AMBITOS ámbitos."
else
    aviso "Sin ADMIN_USUARIO y ADMIN_PASSWORD no se prueba el login."
fi

log "La restauración funciona: el backup se restauró completo y la app levantó con esos datos."
