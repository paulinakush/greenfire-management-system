"""Carga de desarrollo: deja un punto de venta activo en cada ámbito activo, con los comprobantes X.

Lo hace por la API, como el administrador técnico (ADMIN_USUARIO y ADMIN_PASSWORD, del entorno o del .env).
Se puede correr varias veces: si el ámbito ya tiene el punto de venta, le suma los tipos X que falten y lo activa.
Solo funciona contra una API local (GREENFIRE_API, http://localhost:8080 por defecto); nunca contra producción.

Uso: python3 ops/desarrollo/puntos-venta-x.py
"""
import http.cookiejar
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

# Los tipos X que usan los módulos de la etapa 3 (ver docs/plan-etapa-3.md, punto 6).
TIPOS_X = ["comprobante_venta", "nota_credito", "nota_debito", "presupuesto", "recibo", "remito"]
DESCRIPCION = "Punto de venta X (desarrollo)"

RAIZ = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
API = os.environ.get("GREENFIRE_API", "http://localhost:8080").rstrip("/")


def variable(nombre):
    if os.environ.get(nombre):
        return os.environ[nombre]
    ruta = os.path.join(RAIZ, ".env")
    if os.path.exists(ruta):
        with open(ruta, encoding="latin-1") as env:
            for linea in env:
                clave, _, valor = linea.rstrip("\r\n").partition("=")
                if clave == nombre and valor:
                    return valor
    sys.exit(f"Falta la variable {nombre}.")


class Api:
    def __init__(self):
        self.cookies = http.cookiejar.CookieJar()
        self.abrir = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.cookies)).open
        self.pedir("GET", "/api/auth/csrf")

    def pedir(self, metodo, ruta, cuerpo=None):
        pedido = urllib.request.Request(API + ruta, method=metodo,
                                        data=None if cuerpo is None else json.dumps(cuerpo).encode())
        pedido.add_header("Content-Type", "application/json")
        xsrf = next((c.value for c in self.cookies if c.name == "XSRF-TOKEN"), "")
        pedido.add_header("X-XSRF-TOKEN", xsrf)
        try:
            with self.abrir(pedido) as respuesta:
                texto = respuesta.read().decode()
        except urllib.error.HTTPError as e:
            sys.exit(f"{metodo} {ruta}: HTTP {e.code} {e.read().decode()}")
        return json.loads(texto) if texto else None


def main():
    if urllib.parse.urlparse(API).hostname not in ("localhost", "127.0.0.1"):
        sys.exit("Esta carga es solo para desarrollo: GREENFIRE_API tiene que ser localhost.")
    api = Api()
    api.pedir("POST", "/api/auth/login", {"usuario": variable("ADMIN_USUARIO"), "password": variable("ADMIN_PASSWORD")})
    for ambito in api.pedir("GET", "/api/admin/ambitos"):
        if not ambito["activo"]:
            continue
        puntos = api.pedir("GET", f"/api/admin/puntos-venta?ambitoId={ambito['id']}")
        punto = next((p for p in puntos if p["descripcion"] == DESCRIPCION), None)
        comprobantes = [{"tipo": t, "letra": "X"} for t in TIPOS_X]
        if punto is None:
            # El número es por entidad fiscal: el siguiente libre entre los de esa entidad.
            todos = api.pedir("GET", "/api/admin/puntos-venta")
            usados = [p["numero"] for p in todos if p["entidadFiscalId"] == ambito["entidadFiscalId"]]
            punto = api.pedir("POST", "/api/admin/puntos-venta", {
                "ambitoId": ambito["id"], "numero": max(usados, default=0) + 1, "descripcion": DESCRIPCION,
                "comprobantes": comprobantes})
            accion = "creado"
        else:
            actuales = {(c["tipo"], c["letra"]) for c in punto["comprobantes"]}
            faltan = [c for c in comprobantes if (c["tipo"], c["letra"]) not in actuales]
            accion = "sin cambios"
            if faltan:
                punto = api.pedir("PUT", f"/api/admin/puntos-venta/{punto['id']}", {
                    "numero": punto["numero"], "descripcion": punto["descripcion"],
                    "comprobantes": punto["comprobantes"] + faltan})
                accion = "actualizado"
            if not punto["activo"]:
                punto = api.pedir("POST", f"/api/admin/puntos-venta/{punto['id']}/activar")
                accion = "activado"
        tipos = ", ".join(f"{c['tipo']} {c['letra']}" for c in punto["comprobantes"])
        print(f"{ambito['nombre']}: punto de venta {punto['numero']} {accion} (activo: {punto['activo']}; {tipos}).")
    api.pedir("POST", "/api/auth/logout")


if __name__ == "__main__":
    main()
