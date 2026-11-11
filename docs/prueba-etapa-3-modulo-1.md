# Prueba del módulo 1 de la etapa 3 (clientes) contra la base de desarrollo

Fecha: 10 de noviembre de 2026. Rama `etapa-3-clientes-ventas-caja`, con el commit `e6d0ee6` (clientes).

## 1. Respaldo y migración V11

- **Respaldo:**
  - Antes de aplicar V11, con la base en la versión 10, saqué un respaldo completo con `pg_dump -Fc`.
  - Queda fuera del repositorio, en una carpeta de respaldos del usuario, con permisos 600.
  - Lo verifiqué con `pg_restore --list`: tiene las 23 entradas `TABLE DATA`, una por tabla.
- **Primera aplicación (descartada):**
  - V11 se aplicó sin errores, pero el recorrido con curl falló en el alta del mayorista: el CUIT que usé tenía mal el dígito verificador y la API respondió 400, como corresponde.
  - El intento dejó en la base una lista de precios y un cliente de prueba a medias.
- **Restauración:**
  - Borré el esquema `public` y restauré el respaldo con `pg_restore`.
  - La base volvió a la versión 10, con sus 8 usuarios y sus 4 ámbitos. Esto sirvió además para probar que el respaldo se restaura bien.
- **Segunda aplicación:**
  - Levanté la app con esta rama y Flyway aplicó V11 a las 22:45 sobre los datos restaurados, sin errores.
  - Repetí el recorrido con un CUIT válido.

## 2. Historial de Flyway

- **`flyway_schema_history`**, todas con `success = true`:
  - 1 base, 2 auditoria inmutable, 3 auditoria ambito afectado, 4 catalogo, 5 listas precios, 6 stock, 7 conteos, 8 local central, 9 entregas y 10 usuario login: instaladas el 10 de noviembre a las 17:34, cuando se recreó la base de desarrollo.
  - 11 clientes: instalada el 10 de noviembre a las 22:45.

## 3. Cada local es cliente de la fábrica

- **Consulta:** cada local tiene exactamente un cliente que lo representa, y ese cliente es de la fábrica.
- **Local La Rioja:** CUIT 20300000011, monotributo.
- **Local Mitre:** CUIT 30720000020, exento.
- **Local Ruta 1:** CUIT 27310000030, responsable inscripto.
- **Los tres:**
  - Tienen la razón social, el CUIT y la condición frente al IVA de su entidad fiscal.
  - Tienen la cuenta corriente habilitada y no tienen límite de crédito.
  - Los creó la migración sobre los locales existentes, sin intervención manual.

## 4. Recorrido con curl

- **Sesiones:**
  - Cada persona usó su propia sesión: cookie `SESSION` más el token CSRF de `GET /api/auth/csrf`, enviado en el header `X-XSRF-TOKEN`.
  - Las cuentas son las de prueba de la base de desarrollo. El script leyó las contraseñas de un archivo fuera del repositorio y no las mostró.
  - Forma de cada pedido, con variables en lugar de datos reales:
    - `curl -b $JAR -c $JAR -H "X-XSRF-TOKEN: $XSRF" -H 'Content-Type: application/json' -X POST $API/api/clientes -d @alta.json`
- **1. Dueño de fábrica, entra y elige la fábrica:** `POST /api/auth/ambito` responde 200, con ámbito activo Fábrica y perfil `dueno_fabrica`.
- **2. Dueño de fábrica, crea la lista "Mayorista Jardinería":** 201, con id 1, sin IVA y `"discriminaIva":true`.
- **3. Dueño de fábrica, da de alta un mayorista:**
  - "Vivero de Prueba SRL", CUIT 30711111111, responsable inscripto.
  - Condiciones: cuenta corriente, límite 500000, la lista "Mayorista Jardinería" y plazo de 30 días.
  - Responde 201, con id 4 y todas las condiciones.
- **4. Dueño de fábrica, lista sus clientes:** 200, con `total` 4.
  - Los tres locales, con su CUIT, su condición, cuenta corriente y sin límite, lista ni plazo.
  - El mayorista, con límite 500000.00, la lista y 30 días.
- **5. Dueño del Local La Rioja, da de alta un cliente:**
  - "Cliente de Prueba", DNI 11111111.
  - Condiciones: cuenta corriente, límite 80000 y plazo de 15 días.
  - Responde 201, con id 5. Queda como consumidor final, sin lista.
- **6. Empleado del Local La Rioja, lo busca con `GET /api/clientes?q=prueba`:** 200, con `total` 1.
  - Recibe identificación, contacto y `"cuentaCorrienteHabilitada":true`.
  - **No recibe `limiteCredito`, `listaPreciosId`, `listaPreciosNombre` ni `plazoPagoDias`:** las claves no están en la respuesta.
- **7. Empleado, intenta tocar condiciones comerciales:**
  - Alta de un cliente con condiciones: 403.
  - `PUT /api/clientes/5/condiciones`: 403.
  - `POST /api/clientes/5/desactivar`: 403.
- **8. Dueño del Local La Rioja, pide el mismo cliente:** `GET /api/clientes/5` responde 200, con `limiteCredito` 80000.00 y `plazoPagoDias` 15.
- **9. Otros ámbitos, piden el cliente 5:**
  - El dueño de fábrica: 404.
  - La dueña del Local Mitre: 404.

## 5. Auditoría

- **Quedaron tres registros, con el ámbito actuante y el afectado:**
  - `crear` de `lista_precios` 1, por `dueno_fabrica`, en Fábrica.
  - `crear` de `cliente` 4, por `dueno_fabrica`, en Fábrica, con las condiciones: límite 500000, lista 1 y plazo 30.
  - `crear` de `cliente` 5, por `dueno_local`, en Local La Rioja, con límite 80000 y plazo 15.
- **Los intentos del empleado** que respondieron 403 no dejaron registro, porque no cambiaron nada.

## 6. Estado final

- La base de desarrollo queda en la versión 11, con el mayorista, el cliente de prueba y la lista mayorista creados en el recorrido.
- La app sigue levantada con esta rama en `http://localhost:8080`.
