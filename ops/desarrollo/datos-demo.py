"""Carga de desarrollo: lo mínimo para registrar una venta en el Local La Rioja desde la pantalla de sucursal.

Todo por la API, nunca por SQL, y solo contra una API local (GREENFIRE_API, http://localhost:8080 por defecto).
Se puede correr varias veces: cada paso busca primero lo que ya existe y solo crea o corrige lo que falta.

Usuarios: el administrador técnico (ADMIN_USUARIO y ADMIN_PASSWORD, del entorno o del .env) crea o reactiva dos
usuarios temporales, un dueño de fábrica y un dueño del Local La Rioja, con una contraseña aleatoria que no se
guarda ni se muestra. Al terminar, aunque algo falle, los dos quedan desactivados.

Carga:
1. Fábrica: categorías Sustratos, Iluminación y Fertilizantes; el producto terminado "Sustrato de prueba" (IVA 21,
   visible en la web) con su costo; la lista "Lista para locales (prueba)" con su precio; 200 unidades en la
   ubicación Principal con un conteo contra cero, cerrado.
2. Entrega de 50 unidades al Local La Rioja con esa lista, recibida completa por el dueño del local.
3. Local La Rioja: el artículo de reventa "Fertilizante de prueba" (IVA 21) con su costo; la lista predeterminada
   (si no tiene, crea "Venta al público (prueba)") con precio para los dos; 30 unidades del fertilizante con un
   conteo; y el cliente "Cliente de prueba".
4. Abre la caja del local con 5000 de saldo inicial, si no hay una abierta.
5. Muestra un resumen sin secretos: qué se creó, el stock de cada ubicación y los precios.

Uso: python3 ops/desarrollo/datos-demo.py
"""
import http.cookiejar
import json
import os
import secrets
import sys
import urllib.error
import urllib.parse
import urllib.request
from decimal import Decimal

RAIZ = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
API = os.environ.get("GREENFIRE_API", "http://localhost:8080").rstrip("/")

LOCAL = "Local La Rioja"
CATEGORIAS = ["Sustratos", "Iluminación", "Fertilizantes"]
SUSTRATO = "Sustrato de prueba"
FERTILIZANTE = "Fertilizante de prueba"
CLIENTE = "Cliente de prueba"
LISTA_LOCALES = "Lista para locales (prueba)"
LISTA_PUBLICO = "Venta al público (prueba)"
COSTO_SUSTRATO = Decimal("3000.00")
PRECIO_SUSTRATO_LOCALES = Decimal("4500.00")
COSTO_FERTILIZANTE = Decimal("3500.00")
PRECIO_PUBLICO = {SUSTRATO: Decimal("9000.00"), FERTILIZANTE: Decimal("6000.00")}
STOCK_FABRICA = Decimal("200")
ENTREGA = Decimal("50")
STOCK_FERTILIZANTE = Decimal("30")
SALDO_INICIAL_CAJA = Decimal("5000.00")
MARCA = "Carga de datos de demostración (ops/desarrollo/datos-demo.py)"

TEMPORALES = {
    "fabrica": {"usuario": "demo.fabrica", "nombre": "Demo fabrica (temporal)",
                "email": "demo.fabrica@greenfire.test", "perfil": "dueno_fabrica"},
    "local": {"usuario": "demo.larioja", "nombre": "Demo La Rioja (temporal)",
              "email": "demo.larioja@greenfire.test", "perfil": "dueno_local"},
}

creado = []  # lo que hizo esta corrida, para el resumen


def anotar(texto):
    creado.append(texto)
    print("  " + texto)


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
    """Una sesión de la API, con su cookie y el token CSRF en cada pedido."""

    def __init__(self, quien):
        self.quien = quien
        self.cookies = http.cookiejar.CookieJar()
        self.abrir = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(self.cookies)).open
        self.pedir("GET", "/api/auth/csrf")

    def pedir(self, metodo, ruta, cuerpo=None):
        pedido = urllib.request.Request(API + ruta, method=metodo,
                                        data=None if cuerpo is None else json.dumps(cuerpo, default=str).encode())
        pedido.add_header("Content-Type", "application/json")
        pedido.add_header("X-XSRF-TOKEN", next((c.value for c in self.cookies if c.name == "XSRF-TOKEN"), ""))
        try:
            with self.abrir(pedido) as respuesta:
                texto = respuesta.read().decode()
        except urllib.error.HTTPError as e:
            # Nunca se muestra el cuerpo del pedido: puede llevar una contraseña.
            sys.exit(f"[{self.quien}] {metodo} {ruta}: HTTP {e.code} {e.read().decode()}")
        except urllib.error.URLError as e:
            sys.exit(f"No se pudo conectar con {API}: {e.reason}. ¿Está levantada la aplicación?")
        return json.loads(texto, parse_float=Decimal) if texto else None

    def login(self, usuario, password, ambito_id=None):
        self.pedir("POST", "/api/auth/login", {"usuario": usuario, "password": password})
        sesion = self.pedir("GET", "/api/auth/me")
        if ambito_id is not None and (sesion["ambitoActivo"] or {}).get("ambitoId") != ambito_id:
            self.pedir("POST", "/api/auth/ambito", {"ambitoId": ambito_id})

    def salir(self):
        self.pedir("POST", "/api/auth/logout")


def dec(valor):
    return None if valor is None else Decimal(str(valor))


def cantidad(valor):
    return format(dec(valor).normalize(), "f")


def importe(valor):
    return f"{dec(valor):.2f}"


def por_nombre(lista, nombre, campo="nombre"):
    return next((x for x in lista if x[campo] == nombre), None)


# --- Usuarios temporales ---

def password_aleatoria():
    return "Demo-" + secrets.token_urlsafe(24)


def preparar_temporal(admin, datos, ambito_id):
    """Crea el usuario o lo reactiva, con una contraseña nueva y su perfil activo en el ámbito. Devuelve (id, clave)."""
    clave = password_aleatoria()
    usuario = por_nombre(admin.pedir("GET", "/api/admin/usuarios"), datos["usuario"], "usuario")
    if usuario is None:
        usuario = admin.pedir("POST", "/api/admin/usuarios", {
            "nombre": datos["nombre"], "usuario": datos["usuario"], "email": datos["email"], "password": clave,
            "ambitoId": ambito_id, "perfil": datos["perfil"]})
        print(f"  usuario temporal {datos['usuario']}: creado")
        return usuario["id"], clave
    if not usuario["activo"]:
        admin.pedir("POST", f"/api/admin/usuarios/{usuario['id']}/activar")
    admin.pedir("POST", f"/api/admin/usuarios/{usuario['id']}/password", {"password": clave})
    perfil = next((p for p in usuario["perfiles"] if p["ambitoId"] == ambito_id), None)
    if perfil is None:
        admin.pedir("POST", f"/api/admin/usuarios/{usuario['id']}/perfiles",
                    {"ambitoId": ambito_id, "perfil": datos["perfil"]})
    elif not perfil["activo"]:
        admin.pedir("POST", f"/api/admin/usuarios/{usuario['id']}/perfiles/{ambito_id}/activar")
    print(f"  usuario temporal {datos['usuario']}: reactivado con una contraseña nueva")
    return usuario["id"], clave


# --- Pasos reutilizables ---

def principal(api):
    ubicacion = por_nombre(api.pedir("GET", "/api/ubicaciones"), "Principal")
    if ubicacion is None:
        sys.exit(f"[{api.quien}] El ámbito no tiene la ubicación Principal.")
    return ubicacion["id"]


def articulo(api, nombre, tipo, categoria_id):
    encontrados = api.pedir("GET", "/api/articulos?q=" + urllib.parse.quote(nombre))
    a = por_nombre(encontrados, nombre)
    if a is None:
        a = api.pedir("POST", "/api/articulos", {
            "tipo": tipo, "nombre": nombre, "categoriaId": categoria_id, "unidadMedida": "unidad",
            "alicuotaIva": 21, "visibleWeb": tipo == "producto_terminado"})
        anotar(f"artículo '{nombre}' ({tipo}): creado")
    elif not a["activo"]:
        a = api.pedir("POST", f"/api/articulos/{a['id']}/activar")
        anotar(f"artículo '{nombre}': reactivado")
    return a


def costo(api, a, valor):
    if dec(a.get("costo")) != valor:
        api.pedir("PUT", f"/api/articulos/{a['id']}/costo", {"costo": valor})
        anotar(f"costo de '{a['nombre']}' en {api.quien}: {valor}")


def lista(api, nombre, precios_con_iva, predeterminada):
    listas = api.pedir("GET", "/api/listas-precios")
    if predeterminada:
        existente = next((l for l in listas if l["esPredeterminada"] and l["activo"]), None)
    else:
        existente = por_nombre(listas, nombre)
    if existente is None:
        existente = api.pedir("POST", "/api/listas-precios", {
            "nombre": nombre, "preciosConIva": precios_con_iva, "esPredeterminada": predeterminada})
        anotar(f"lista '{nombre}' en {api.quien}: creada")
    elif not existente["activo"]:
        existente = api.pedir("POST", f"/api/listas-precios/{existente['id']}/activar")
        anotar(f"lista '{existente['nombre']}' en {api.quien}: reactivada")
    return existente


def precio(api, lista_, a, valor):
    items = api.pedir("GET", f"/api/listas-precios/{lista_['id']}/items")
    actual = next((i for i in items if i["articuloId"] == a["id"]), None)
    if actual is None or dec(actual["precio"]) != valor:
        api.pedir("PUT", f"/api/listas-precios/{lista_['id']}/items/{a['id']}", {"precio": valor})
        anotar(f"precio de '{a['nombre']}' en '{lista_['nombre']}': {valor}")


def stock_inicial(api, a, ubicacion_id, cantidad):
    """Un conteo contra cero, cerrado. Solo si el artículo nunca tuvo movimientos en esa ubicación."""
    movimientos = api.pedir("GET", f"/api/stock/movimientos?articuloId={a['id']}&ubicacionId={ubicacion_id}&tamano=1")
    if movimientos["total"] > 0:
        return
    abiertos = api.pedir("GET", f"/api/conteos?ubicacionId={ubicacion_id}&estado=abierto")
    conteo = next((c for c in abiertos if c["observaciones"] == MARCA), None)
    if conteo is None:
        conteo = api.pedir("POST", "/api/conteos", {"ubicacionId": ubicacion_id, "observaciones": MARCA})
    api.pedir("PUT", f"/api/conteos/{conteo['id']}/items",
              {"items": [{"articuloId": a["id"], "cantidadContada": cantidad}]})
    api.pedir("POST", f"/api/conteos/{conteo['id']}/cerrar")
    anotar(f"stock inicial de '{a['nombre']}' en {api.quien}: conteo {conteo['id']} cerrado con {cantidad}")


# --- Carga ---

def cargar_fabrica(fab, local_id):
    print("Fábrica:")
    categorias = fab.pedir("GET", "/api/categorias")
    ids = {}
    for nombre in CATEGORIAS:
        c = por_nombre(categorias, nombre)
        if c is None:
            c = fab.pedir("POST", "/api/categorias", {"nombre": nombre})
            anotar(f"categoría '{nombre}': creada")
        elif not c["activo"]:
            c = fab.pedir("POST", f"/api/categorias/{c['id']}/activar")
            anotar(f"categoría '{nombre}': reactivada")
        ids[nombre] = c["id"]
    ubicacion = principal(fab)
    sustrato = articulo(fab, SUSTRATO, "producto_terminado", ids["Sustratos"])
    costo(fab, sustrato, COSTO_SUSTRATO)
    para_locales = lista(fab, LISTA_LOCALES, False, False)
    precio(fab, para_locales, sustrato, PRECIO_SUSTRATO_LOCALES)
    stock_inicial(fab, sustrato, ubicacion, STOCK_FABRICA)

    entregas = [e for e in fab.pedir("GET", "/api/entregas")
                if e["localId"] == local_id and e["estado"] != "anulada"
                and any(r["articuloId"] == sustrato["id"] for r in e["renglones"])]
    if entregas:
        entrega = entregas[0]
    else:
        entrega = fab.pedir("POST", "/api/entregas", {
            "localId": local_id, "ubicacionOrigenId": ubicacion, "listaPreciosId": para_locales["id"],
            "observaciones": MARCA, "renglones": [{"articuloId": sustrato["id"], "cantidad": ENTREGA}]})
        anotar(f"entrega {entrega['id']} de {ENTREGA} '{SUSTRATO}' al {LOCAL}: enviada")
    return ids, sustrato, para_locales, entrega["id"]


def cargar_local(loc, ids, sustrato, entrega_id):
    print(f"{LOCAL}:")
    ubicacion = principal(loc)
    entrega = loc.pedir("GET", f"/api/entregas/{entrega_id}")
    if entrega["estado"] == "enviada":
        loc.pedir("POST", f"/api/entregas/{entrega_id}/recepcion", {
            "ubicacionId": ubicacion, "observaciones": MARCA,
            "renglones": [{"renglonId": r["id"], "cantidadRecibida": r["cantidadEnviada"]} for r in entrega["renglones"]]})
        anotar(f"entrega {entrega_id}: recibida completa en Principal")
    fertilizante = articulo(loc, FERTILIZANTE, "reventa", ids["Fertilizantes"])
    costo(loc, fertilizante, COSTO_FERTILIZANTE)
    publico = lista(loc, LISTA_PUBLICO, True, True)
    for a in (sustrato, fertilizante):
        precio(loc, publico, a, PRECIO_PUBLICO[a["nombre"]])
    stock_inicial(loc, fertilizante, ubicacion, STOCK_FERTILIZANTE)

    clientes = loc.pedir("GET", "/api/clientes?tamano=100&q=" + urllib.parse.quote(CLIENTE))["clientes"]
    cliente = por_nombre(clientes, CLIENTE)
    if cliente is None:
        loc.pedir("POST", "/api/clientes", {"datos": {"nombre": CLIENTE, "condicionIva": "consumidor_final"}})
        anotar(f"cliente '{CLIENTE}': creado")
    elif not cliente["activo"]:
        loc.pedir("POST", f"/api/clientes/{cliente['id']}/activar")
        anotar(f"cliente '{CLIENTE}': reactivado")

    if loc.pedir("GET", "/api/caja/actual") is None:
        loc.pedir("POST", "/api/caja/abrir", {"saldoInicial": SALDO_INICIAL_CAJA})
        anotar(f"caja del {LOCAL}: abierta con {SALDO_INICIAL_CAJA}")
    return publico


def resumen(fab, loc, para_locales, publico):
    print("\nResumen")
    print("  Esta corrida: " + ("nada nuevo, todo estaba cargado." if not creado else f"{len(creado)} cambios (arriba)."))
    nombres = (SUSTRATO, FERTILIZANTE)
    for api, ambito in ((fab, "Fábrica"), (loc, LOCAL)):
        for s in api.pedir("GET", "/api/stock"):
            if s["articuloNombre"] in nombres:
                print(f"  Stock en {ambito}, {s['ubicacionNombre']}: {s['articuloNombre']} = {cantidad(s['cantidad'])}")
    for api, l in ((fab, para_locales), (loc, publico)):
        modo = "con IVA" if l["preciosConIva"] else "sin IVA"
        for i in api.pedir("GET", f"/api/listas-precios/{l['id']}/items"):
            if i["articuloNombre"] in nombres:
                print(f"  Precio en '{l['nombre']}' ({api.quien}, {modo}): {i['articuloNombre']} = {importe(i['precio'])}")
    for a in loc.pedir("GET", "/api/articulos?q=de%20prueba"):
        if a["nombre"] in nombres:
            print(f"  Costo en {LOCAL}: {a['nombre']} = {importe(a['costo'])}")
    caja = loc.pedir("GET", "/api/caja/actual")
    print(f"  Caja del {LOCAL}: " + (f"abierta, saldo inicial {importe(caja['saldoInicial'])}" if caja else "cerrada"))


def main():
    if urllib.parse.urlparse(API).hostname not in ("localhost", "127.0.0.1"):
        sys.exit("Esta carga es solo para desarrollo: GREENFIRE_API tiene que ser localhost.")
    admin = Api("administrador técnico")
    admin.login(variable("ADMIN_USUARIO"), variable("ADMIN_PASSWORD"))
    ambitos = admin.pedir("GET", "/api/admin/ambitos")
    fabrica = next((a for a in ambitos if a["tipo"] == "fabrica" and a["activo"]), None)
    local = next((a for a in ambitos if a["nombre"] == LOCAL and a["activo"]), None)
    if fabrica is None or local is None:
        sys.exit(f"Faltan la fábrica o el {LOCAL}, activos.")

    print("Usuarios temporales:")
    ids = []
    try:
        fab_id, fab_clave = preparar_temporal(admin, TEMPORALES["fabrica"], fabrica["id"])
        ids.append(fab_id)
        loc_id, loc_clave = preparar_temporal(admin, TEMPORALES["local"], local["id"])
        ids.append(loc_id)
        fab, loc = Api("fábrica"), Api(LOCAL)
        fab.login(TEMPORALES["fabrica"]["usuario"], fab_clave, fabrica["id"])
        loc.login(TEMPORALES["local"]["usuario"], loc_clave, local["id"])
        del fab_clave, loc_clave

        categorias, sustrato, para_locales, entrega_id = cargar_fabrica(fab, local["id"])
        publico = cargar_local(loc, categorias, sustrato, entrega_id)
        resumen(fab, loc, para_locales, publico)
        fab.salir()
        loc.salir()
    finally:
        for usuario_id in ids:
            admin.pedir("POST", f"/api/admin/usuarios/{usuario_id}/desactivar")
        if ids:
            print("  Usuarios temporales desactivados.")
        admin.salir()


if __name__ == "__main__":
    main()
