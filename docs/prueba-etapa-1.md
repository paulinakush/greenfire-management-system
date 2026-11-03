# Prueba de la etapa 1 con curl

Recorrido ejecutado el 2026-11-02 contra la app levantada con `./mvnw spring-boot:run` y la base de `docker compose`. Los pasos 1 a 5 se hicieron con las migraciones en v2; la última parte repite el recorrido con V3.

## Cómo se hicieron los pedidos

- Cada usuario usa su propio archivo de cookies (`admin.jar`, `dueno.jar`), que guarda la cookie de sesión `SESSION` y el token `XSRF-TOKEN`.
- Antes del login se pide `GET /api/auth/csrf` para obtener el token, y en cada POST, PUT o DELETE se manda ese valor en el header `X-XSRF-TOKEN`.
- Las contraseñas no aparecen en este documento: la del administrador sale de `ADMIN_PASSWORD` en el `.env`, y la del dueño se generó al azar en el momento (`$DUENO_PASSWORD`).
- Funciones usadas:

```sh
API=http://localhost:8080
pedir() { # jar método ruta [cuerpo]
  local jar=$1 metodo=$2 ruta=$3 cuerpo=${4:-}
  local xsrf; xsrf=$(awk '$6=="XSRF-TOKEN"{print $7}' "$jar" 2>/dev/null)
  local args=(-s -b "$jar" -c "$jar" -X "$metodo" -H "X-XSRF-TOKEN: $xsrf" -w '\nHTTP %{http_code}\n')
  [ -n "$cuerpo" ] && args+=(-H 'Content-Type: application/json' -d "$cuerpo")
  curl "${args[@]}" "$API$ruta"
}
entrar() { # jar email password
  rm -f "$1"; curl -s -c "$1" "$API/api/auth/csrf" >/dev/null
  pedir "$1" POST /api/auth/login "{\"email\":\"$2\",\"password\":\"$3\"}"
}
```

## 1. Entrar como administrador técnico

- Comando: `entrar admin.jar "$ADMIN_EMAIL" "$ADMIN_PASSWORD"`
- Resultado: 200. El administrador no tiene perfiles ni ámbito activo.

```
{"usuario":{"id":1,"nombre":"Administrador técnico","email":"admin@greenfire.local","adminTecnico":true},"perfiles":[],"ambitoActivo":null}
HTTP 200
```

## 2. Crear un dueño de local

- La base de desarrollo no tenía ningún local, así que primero el administrador crea la entidad fiscal y el local, y después el dueño.
- 2a. Entidad fiscal: `pedir admin.jar POST /api/admin/entidades-fiscales '{"razonSocial":"Green Fire Palermo SRL","cuit":"30712345671","condicionIva":"responsable_inscripto","domicilioFiscal":"Honduras 5000, CABA","arcaCertificadoRef":"vault://arca/palermo","arcaCertificadoVence":"2027-05-01"}'`

```
{"id":2,"razonSocial":"Green Fire Palermo SRL","cuit":"30712345671","condicionIva":"responsable_inscripto","domicilioFiscal":"Honduras 5000, CABA","arcaCertificadoRef":"vault://arca/palermo","arcaCertificadoVence":"2027-05-01","activo":true}
HTTP 201
```

- 2b. Local: `pedir admin.jar POST /api/admin/ambitos '{"tipo":"local","nombre":"Local Palermo","entidadFiscalId":2,"visibleWeb":true}'`

```
{"id":1,"tipo":"local","nombre":"Local Palermo","entidadFiscalId":2,"entidadFiscalRazonSocial":"Green Fire Palermo SRL","direccion":null,"telefono":null,"email":null,"horarios":null,"visibleWeb":true,"activo":true}
HTTP 201
```

- 2c. Dueño: `pedir admin.jar POST /api/admin/usuarios '{"nombre":"Marta Dueña","email":"marta@greenfire.local","password":"'$DUENO_PASSWORD'","ambitoId":1,"perfil":"dueno_local"}'`
- Resultado: 201. La respuesta no incluye la contraseña ni su hash.

```
{"id":2,"nombre":"Marta Dueña","email":"marta@greenfire.local","activo":true,"bloqueado":false,"ultimoAcceso":null,"perfiles":[{"ambitoId":1,"ambitoNombre":"Local Palermo","ambitoTipo":"local","perfil":"dueno_local","activo":true}]}
HTTP 201
```

## 3. Entrar como ese dueño

- 3a. Login: `entrar dueno.jar marta@greenfire.local "$DUENO_PASSWORD"`
- Resultado: 200. Como tiene un solo perfil, la sesión queda directamente en el Local Palermo.

```
{"usuario":{"id":2,"nombre":"Marta Dueña","email":"marta@greenfire.local","adminTecnico":false},"perfiles":[{"ambitoId":1,"ambitoNombre":"Local Palermo","ambitoTipo":"local","perfil":"dueno_local"}],"ambitoActivo":{"ambitoId":1,"ambitoNombre":"Local Palermo","ambitoTipo":"local","perfil":"dueno_local"}}
HTTP 200
```

- 3b. Para que haya algo en la auditoría de su ámbito, el dueño edita el contacto de su local: `pedir dueno.jar PUT /api/ambito/contacto '{"direccion":"Honduras 5000, CABA","horarios":"Lun a Sab 10 a 20"}'`

```
{"id":1,"tipo":"local","nombre":"Local Palermo","entidadFiscalId":2,"entidadFiscalRazonSocial":"Green Fire Palermo SRL","direccion":"Honduras 5000, CABA","telefono":null,"email":null,"horarios":"Lun a Sab 10 a 20","visibleWeb":true,"activo":true}
HTTP 200
```

## 4. Consultar la auditoría como dueño y como administrador técnico

- 4a. Dueño: `pedir dueno.jar GET /api/auditoria`
- Resultado: 200 con 2 registros, los dos del Local Palermo: su login y la edición del contacto, con valor anterior y nuevo.

```
{"registros":[{"id":8,"fechaHora":"2026-11-02T20:40:25.863142Z","usuarioId":2,"usuarioNombre":"Marta Dueña","perfil":"dueno_local","ambitoId":1,"ambitoNombre":"Local Palermo","accion":"editar_contacto","entidad":"ambito","entidadId":1,"detalle":{"direccion":{"antes":null,"despues":"Honduras 5000, CABA"},"horarios":{"antes":null,"despues":"Lun a Sab 10 a 20"}},"emailIntentado":null,"ip":"0:0:0:0:0:0:0:1"},{"id":7,"fechaHora":"2026-11-02T20:40:25.826485Z","usuarioId":2,"usuarioNombre":"Marta Dueña","perfil":"dueno_local","ambitoId":1,"ambitoNombre":"Local Palermo","accion":"login","entidad":"usuario","entidadId":2,"detalle":{},"emailIntentado":null,"ip":"0:0:0:0:0:0:0:1"}],"pagina":0,"tamano":50,"total":2}
HTTP 200
```

- 4b. Administrador técnico: `pedir admin.jar GET /api/auditoria`
- Resultado: 200 con los 8 registros de la base.

```
{"registros":[{"id":8,"fechaHora":"2026-11-02T20:40:25.863142Z","usuarioId":2,"usuarioNombre":"Marta Dueña","perfil":"dueno_local","ambitoId":1,"ambitoNombre":"Local Palermo","accion":"editar_contacto","entidad":"ambito","entidadId":1,"detalle":{"direccion":{"antes":null,"despues":"Honduras 5000, CABA"},"horarios":{"antes":null,"despues":"Lun a Sab 10 a 20"}},"emailIntentado":null,"ip":"0:0:0:0:0:0:0:1"},{"id":7,"fechaHora":"2026-11-02T20:40:25.826485Z","usuarioId":2,"usuarioNombre":"Marta Dueña","perfil":"dueno_local","ambitoId":1,"ambitoNombre":"Local Palermo","accion":"login","entidad":"usuario","entidadId":2,"detalle":{},"emailIntentado":null,"ip":"0:0:0:0:0:0:0:1"},{"id":6,"fechaHora":"2026-11-02T20:40:25.693415Z","usuarioId":1,"usuarioNombre":"Administrador técnico","perfil":null,"ambitoId":null,"ambitoNombre":null,"accion":"crear","entidad":"usuario","entidadId":2,"detalle":{"nombre":"Marta Dueña","email":"marta@greenfire.local","ambitoId":1,"perfil":"dueno_local"},"emailIntentado":null,"ip":"0:0:0:0:0:0:0:1"},{"id":5,"fechaHora":"2026-11-02T20:40:25.546122Z","usuarioId":1,"usuarioNombre":"Administrador técnico","perfil":null,"ambitoId":null,"ambitoNombre":null,"accion":"crear","entidad":"ambito","entidadId":1,"detalle":{"tipo":"local","nombre":"Local Palermo","entidadFiscalId":2,"visibleWeb":true},"emailIntentado":null,"ip":"0:0:0:0:0:0:0:1"},{"id":4,"fechaHora":"2026-11-02T20:40:25.502041Z","usuarioId":1,"usuarioNombre":"Administrador técnico","perfil":null,"ambitoId":null,"ambitoNombre":null,"accion":"crear","entidad":"entidad_fiscal","entidadId":2,"detalle":{"razonSocial":"Green Fire Palermo SRL","cuit":"30712345671","condicionIva":"responsable_inscripto"},"emailIntentado":null,"ip":"0:0:0:0:0:0:0:1"},{"id":3,"fechaHora":"2026-11-02T20:40:25.397492Z","usuarioId":1,"usuarioNombre":"Administrador técnico","perfil":null,"ambitoId":null,"ambitoNombre":null,"accion":"login","entidad":"usuario","entidadId":1,"detalle":{},"emailIntentado":null,"ip":"0:0:0:0:0:0:0:1"},{"id":2,"fechaHora":"2026-10-29T14:21:08.887053Z","usuarioId":1,"usuarioNombre":"Administrador técnico","perfil":null,"ambitoId":null,"ambitoNombre":null,"accion":"login","entidad":"usuario","entidadId":1,"detalle":{},"emailIntentado":null,"ip":"0:0:0:0:0:0:0:1"},{"id":1,"fechaHora":"2026-10-29T14:21:08.667851Z","usuarioId":null,"usuarioNombre":null,"perfil":null,"ambitoId":null,"ambitoNombre":null,"accion":"login_fallido","entidad":"usuario","entidadId":null,"detalle":{"motivo":"email_inexistente"},"emailIntentado":"nadie@x.com","ip":"0:0:0:0:0:0:0:1"}],"pagina":0,"tamano":50,"total":8}
HTTP 200
```

- Diferencia:
  - El dueño ve 2 registros y el administrador ve 8.
  - El dueño ve solo lo que pasó en su ámbito activo (Local Palermo).
  - El administrador ve además sus propias acciones (ids 2 a 6: logins, alta de la entidad fiscal, del local y del dueño), que no tienen ámbito, y el login fallido con el email intentado (id 1).
  - El dueño no ve las acciones del administrador sobre su local (ids 5 y 6), porque en ese momento el registro solo guardaba el ámbito de quien actúa y el administrador no tiene ámbito. Se resolvió con la migración V3 (ver "Después de V3: ámbito afectado").
  - El alta de la entidad fiscal (id 4) no guarda la referencia ni el vencimiento del certificado de ARCA, y el alta del dueño (id 6) no guarda la contraseña.

## 5. Intentar editar o borrar un registro de auditoría

- 5a. Editar por la API: `pedir admin.jar PUT /api/auditoria/8 '{"accion":"otra"}'`. Falla con 404: no existe ninguna ruta para editar un registro.

```
{"timestamp":"2026-11-02T20:40:26.206+00:00","status":404,"error":"Not Found","path":"/api/auditoria/8"}
HTTP 404
```

- 5b. Borrar por la API: `pedir admin.jar DELETE /api/auditoria/8`. Falla con 404, aun siendo el administrador técnico.

```
{"timestamp":"2026-11-02T20:40:26.228+00:00","status":404,"error":"Not Found","path":"/api/auditoria/8"}
HTTP 404
```

- 5c. Borrar todo por la API: `pedir dueno.jar DELETE /api/auditoria`. Falla con 405: la ruta solo acepta GET.

```
{"timestamp":"2026-11-02T20:40:26.249+00:00","status":405,"error":"Method Not Allowed","path":"/api/auditoria"}
HTTP 405
```

- 5d. Directo en la base, con `psql` dentro del contenedor (`update auditorias set accion = 'otra' where id = 8`, `delete from auditorias where id = 8` y `truncate auditorias`). Los tres fallan por los triggers de la migración V2 y la tabla sigue con 8 registros.

```
ERROR:  La auditoría no se puede modificar ni borrar (operación UPDATE rechazada).
CONTEXT:  PL/pgSQL function auditorias_inmutable() line 3 at RAISE
ERROR:  La auditoría no se puede modificar ni borrar (operación DELETE rechazada).
CONTEXT:  PL/pgSQL function auditorias_inmutable() line 3 at RAISE
ERROR:  La auditoría no se puede modificar ni borrar (operación TRUNCATE rechazada).
CONTEXT:  PL/pgSQL function auditorias_inmutable() line 3 at RAISE
 count
-------
     8
(1 row)
```

## Después de V3: ámbito afectado

- La migración V3 agrega `ambito_afectado_id` a `auditorias`. Toda alta, edición o desactivación que toca a un ámbito lo registra como afectado, además del ámbito activo de quien actúa.
- El dueño ve los registros cuyo ámbito actuante o afectado es el suyo. No ve los eventos de login y sesión, ni las acciones del administrador técnico que no tocan su ámbito.
- Los registros anteriores a V3 quedan sin ámbito afectado: completarlos sería modificar la auditoría. Por eso Marta sigue sin ver las altas de los ids 5 y 6.
- Se repitió el recorrido con un local nuevo, con las mismas funciones y otra contraseña generada al azar para el dueño.
- 1. Login del administrador: igual que antes, 200.
- 2a. Entidad fiscal: `pedir admin.jar POST /api/admin/entidades-fiscales '{"razonSocial":"Green Fire Belgrano SRL","cuit":"30712345698","condicionIva":"monotributo","arcaCertificadoRef":"vault://arca/belgrano","arcaCertificadoVence":"2027-08-01"}'`

```
{"id":3,"razonSocial":"Green Fire Belgrano SRL","cuit":"30712345698","condicionIva":"monotributo","domicilioFiscal":null,"arcaCertificadoRef":"vault://arca/belgrano","arcaCertificadoVence":"2027-08-01","activo":true}
HTTP 201
```

- 2b. Local: `pedir admin.jar POST /api/admin/ambitos '{"tipo":"local","nombre":"Local Belgrano","entidadFiscalId":3,"visibleWeb":true}'`

```
{"id":2,"tipo":"local","nombre":"Local Belgrano","entidadFiscalId":3,"entidadFiscalRazonSocial":"Green Fire Belgrano SRL","direccion":null,"telefono":null,"email":null,"horarios":null,"visibleWeb":true,"activo":true}
HTTP 201
```

- 2c. Dueño: `pedir admin.jar POST /api/admin/usuarios '{"nombre":"Tomas Dueno","email":"tomas@greenfire.local","password":"'$DUENO_PASSWORD'","ambitoId":2,"perfil":"dueno_local"}'`

```
{"id":3,"nombre":"Tomas Dueno","email":"tomas@greenfire.local","activo":true,"bloqueado":false,"ultimoAcceso":null,"perfiles":[{"ambitoId":2,"ambitoNombre":"Local Belgrano","ambitoTipo":"local","perfil":"dueno_local","activo":true}]}
HTTP 201
```

- 2d. El administrador renueva el certificado de ARCA de la entidad: `pedir admin.jar PUT /api/admin/entidades-fiscales/3 '{"razonSocial":"Green Fire Belgrano SRL","cuit":"30712345698","condicionIva":"monotributo","arcaCertificadoRef":"vault://arca/belgrano-2","arcaCertificadoVence":"2028-08-01"}'`

```
{"id":3,"razonSocial":"Green Fire Belgrano SRL","cuit":"30712345698","condicionIva":"monotributo","domicilioFiscal":null,"arcaCertificadoRef":"vault://arca/belgrano-2","arcaCertificadoVence":"2028-08-01","activo":true}
HTTP 200
```

- 3a. Login del dueño: `entrar dueno.jar tomas@greenfire.local "$DUENO_PASSWORD"`

```
{"usuario":{"id":3,"nombre":"Tomas Dueno","email":"tomas@greenfire.local","adminTecnico":false},"perfiles":[{"ambitoId":2,"ambitoNombre":"Local Belgrano","ambitoTipo":"local","perfil":"dueno_local"}],"ambitoActivo":{"ambitoId":2,"ambitoNombre":"Local Belgrano","ambitoTipo":"local","perfil":"dueno_local"}}
HTTP 200
```

- 3b. El dueño edita el contacto: `pedir dueno.jar PUT /api/ambito/contacto '{"direccion":"Cabildo 2000, CABA","horarios":"Lun a Sab 10 a 20"}'`

```
{"id":2,"tipo":"local","nombre":"Local Belgrano","entidadFiscalId":3,"entidadFiscalRazonSocial":"Green Fire Belgrano SRL","direccion":"Cabildo 2000, CABA","telefono":null,"email":null,"horarios":"Lun a Sab 10 a 20","visibleWeb":true,"activo":true}
HTTP 200
```

- 4a. Auditoría del dueño: `pedir dueno.jar GET /api/auditoria`. Ve 4 registros: su edición y, hechas por el administrador, el alta de su local, el alta de su cuenta y la renovación del certificado de su entidad fiscal, con la referencia y el vencimiento como `[oculto]`.

```
{"registros":[{"id":15,"fechaHora":"2026-11-02T20:59:06.101223Z","usuarioId":3,"usuarioNombre":"Tomas Dueno","perfil":"dueno_local","ambitoId":2,"ambitoNombre":"Local Belgrano","ambitoAfectadoId":2,"ambitoAfectadoNombre":"Local Belgrano","accion":"editar_contacto","entidad":"ambito","entidadId":2,"detalle":{"direccion":{"antes":null,"despues":"Cabildo 2000, CABA"},"horarios":{"antes":null,"despues":"Lun a Sab 10 a 20"}},"emailIntentado":null,"ip":"0:0:0:0:0:0:0:1"},{"id":13,"fechaHora":"2026-11-02T20:59:05.937701Z","usuarioId":1,"usuarioNombre":"Administrador técnico","perfil":null,"ambitoId":null,"ambitoNombre":null,"ambitoAfectadoId":2,"ambitoAfectadoNombre":"Local Belgrano","accion":"editar","entidad":"entidad_fiscal","entidadId":3,"detalle":{"arcaCertificadoRef":{"antes":"[oculto]","despues":"[oculto]"},"arcaCertificadoVence":{"antes":"[oculto]","despues":"[oculto]"}},"emailIntentado":null,"ip":"0:0:0:0:0:0:0:1"},{"id":12,"fechaHora":"2026-11-02T20:59:05.906872Z","usuarioId":1,"usuarioNombre":"Administrador técnico","perfil":null,"ambitoId":null,"ambitoNombre":null,"ambitoAfectadoId":2,"ambitoAfectadoNombre":"Local Belgrano","accion":"crear","entidad":"usuario","entidadId":3,"detalle":{"nombre":"Tomas Dueno","email":"tomas@greenfire.local","ambitoId":2,"perfil":"dueno_local"},"emailIntentado":null,"ip":"0:0:0:0:0:0:0:1"},{"id":11,"fechaHora":"2026-11-02T20:59:05.753216Z","usuarioId":1,"usuarioNombre":"Administrador técnico","perfil":null,"ambitoId":null,"ambitoNombre":null,"ambitoAfectadoId":2,"ambitoAfectadoNombre":"Local Belgrano","accion":"crear","entidad":"ambito","entidadId":2,"detalle":{"tipo":"local","nombre":"Local Belgrano","entidadFiscalId":3,"visibleWeb":true},"emailIntentado":null,"ip":"0:0:0:0:0:0:0:1"}],"pagina":0,"tamano":50,"total":4}
HTTP 200
```

- 4b. Auditoría del administrador: `pedir admin.jar GET "/api/auditoria?tamano=5"`. Tiene 15 registros en total; en los 5 más recientes aparece además el login del dueño (id 14), que el dueño no ve.

```
{"registros":[{"id":15,"fechaHora":"2026-11-02T20:59:06.101223Z","usuarioId":3,"usuarioNombre":"Tomas Dueno","perfil":"dueno_local","ambitoId":2,"ambitoNombre":"Local Belgrano","ambitoAfectadoId":2,"ambitoAfectadoNombre":"Local Belgrano","accion":"editar_contacto","entidad":"ambito","entidadId":2,"detalle":{"direccion":{"antes":null,"despues":"Cabildo 2000, CABA"},"horarios":{"antes":null,"despues":"Lun a Sab 10 a 20"}},"emailIntentado":null,"ip":"0:0:0:0:0:0:0:1"},{"id":14,"fechaHora":"2026-11-02T20:59:06.063785Z","usuarioId":3,"usuarioNombre":"Tomas Dueno","perfil":"dueno_local","ambitoId":2,"ambitoNombre":"Local Belgrano","ambitoAfectadoId":null,"ambitoAfectadoNombre":null,"accion":"login","entidad":"usuario","entidadId":3,"detalle":{},"emailIntentado":null,"ip":"0:0:0:0:0:0:0:1"},{"id":13,"fechaHora":"2026-11-02T20:59:05.937701Z","usuarioId":1,"usuarioNombre":"Administrador técnico","perfil":null,"ambitoId":null,"ambitoNombre":null,"ambitoAfectadoId":2,"ambitoAfectadoNombre":"Local Belgrano","accion":"editar","entidad":"entidad_fiscal","entidadId":3,"detalle":{"arcaCertificadoRef":{"antes":"[oculto]","despues":"[oculto]"},"arcaCertificadoVence":{"antes":"[oculto]","despues":"[oculto]"}},"emailIntentado":null,"ip":"0:0:0:0:0:0:0:1"},{"id":12,"fechaHora":"2026-11-02T20:59:05.906872Z","usuarioId":1,"usuarioNombre":"Administrador técnico","perfil":null,"ambitoId":null,"ambitoNombre":null,"ambitoAfectadoId":2,"ambitoAfectadoNombre":"Local Belgrano","accion":"crear","entidad":"usuario","entidadId":3,"detalle":{"nombre":"Tomas Dueno","email":"tomas@greenfire.local","ambitoId":2,"perfil":"dueno_local"},"emailIntentado":null,"ip":"0:0:0:0:0:0:0:1"},{"id":11,"fechaHora":"2026-11-02T20:59:05.753216Z","usuarioId":1,"usuarioNombre":"Administrador técnico","perfil":null,"ambitoId":null,"ambitoNombre":null,"ambitoAfectadoId":2,"ambitoAfectadoNombre":"Local Belgrano","accion":"crear","entidad":"ambito","entidadId":2,"detalle":{"tipo":"local","nombre":"Local Belgrano","entidadFiscalId":3,"visibleWeb":true},"emailIntentado":null,"ip":"0:0:0:0:0:0:0:1"}],"pagina":0,"tamano":5,"total":15}
HTTP 200
```

- Diferencia:
  - El dueño no ve su propio login (id 14), ni los logins del administrador, ni el alta de la entidad fiscal (id 10), que se hizo cuando todavía no tenía ningún local.
  - Tampoco ve nada del Local Palermo.
  - La respuesta del dueño no contiene `vault://` ni las fechas de vencimiento del certificado.
- Inmutabilidad después de V3: `update auditorias set ambito_afectado_id = 1 where id = 5` y `delete from auditorias where id = 15` siguen fallando con "La auditoría no se puede modificar ni borrar".

## Tests que cubren cada punto

- (a) Registros inmutables: `AuditoriaRegistroTest`, con `laBaseNoPermiteModificarUnRegistro`, `laBaseNoPermiteBorrarUnRegistro`, `laBaseNoPermiteVaciarLaTabla`, `laApiNoTieneFormaDeEditarNiBorrar` y `elRepositorioSoloPermiteAgregar`.
- (b) Contraseñas y certificado de ARCA fuera de la auditoría: `AuditoriaRegistroTest.nuncaSeGuardanContrasenasHashesTokensNiElCertificadoDeArca` (recorre alta, cambio de contraseña, recuperación por token y edición del certificado, y busca los valores en la tabla), `RedactorTest.ocultaContrasenasHashesTokensYCertificadoAunqueEstenAnidados` y `AuditoriaConsultaApiTest.unDuenoVeLaEdicionDeSuEntidadFiscalSinLosDatosDelCertificadoDeArca`.
- (c) Cada dueño ve solo su ámbito: `AuditoriaConsultaApiTest`, con `unDuenoDeLocalVeSoloLaAuditoriaDeSuLocal`, `unDuenoNoPuedePedirOtroAmbitoPorParametro`, `unDuenoNoVeLosLoginsFallidos`, `unDuenoVeElAltaDeSuCuentaHechaPorElAdministrador`, `unDuenoNoVeAccionesSobreOtroLocal` y `unDuenoNoVeLosLoginsNiLasAccionesDelAdministradorQueNoTocanSuAmbito`.

## Datos que quedaron en la base de desarrollo

- Las entidades fiscales "Green Fire Palermo SRL" (id 2) y "Green Fire Belgrano SRL" (id 3), los locales "Local Palermo" (id 1) y "Local Belgrano" (id 2), y los usuarios marta@greenfire.local (id 2) y tomas@greenfire.local (id 3).
- Los registros de auditoría 3 a 15. Por diseño, no se pueden borrar.
