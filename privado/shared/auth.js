/* ---------------------------------------------------------
   AUTH — login de las interfaces privadas contra la API.

   La sesión vive en el servidor (cookie SESSION). Este archivo:
   - habla con /api/auth/* y manda el token CSRF (cookie XSRF-TOKEN)
     en el header X-XSRF-TOKEN de cada POST, PUT o DELETE;
   - protege cada página interna: sin sesión, al login; con un perfil
     que no corresponde a la página, a la que le corresponde;
   - muestra siempre arriba el ámbito y el perfil activos, con los
     botones para cambiar de ámbito y cerrar sesión.

   Los permisos los valida siempre el servidor: esto solo decide qué
   pantalla mostrar. Hay que abrir todo desde http://localhost:8080
   (con archivos locales no hay cookies ni API).
--------------------------------------------------------- */

(function () {
  const LOGIN = "login.html";
  // Marca que en esta pestaña hubo una sesión: si después la API responde 401, es que venció.
  const HUBO_SESION = "gf_hubo_sesion";

  const PERFILES = {
    dueno_fabrica: "Dueño de fábrica",
    dueno_local: "Dueño de local",
    empleado_local: "Empleado de local",
  };
  const TIPOS_AMBITO = { fabrica: "Fábrica", local: "Local" };

  // --- API ---

  const leerCookie = (nombre) => {
    const par = document.cookie.split("; ").find((c) => c.startsWith(nombre + "="));
    return par ? decodeURIComponent(par.slice(nombre.length + 1)) : null;
  };

  const pedirCsrf = () => fetch("/api/auth/csrf", { credentials: "same-origin" });

  /**
   * Llama a la API y devuelve { ok, status, data }. data es el JSON de la respuesta (o null). Si el token CSRF
   * falta o venció, lo pide de nuevo y reintenta una vez. Un error de red devuelve status 0.
   */
  const api = async (ruta, { method = "GET", body } = {}) => {
    const escribe = method !== "GET";
    const enviar = async () => {
      if (escribe && !leerCookie("XSRF-TOKEN")) await pedirCsrf();
      const headers = { Accept: "application/json" };
      if (body !== undefined) headers["Content-Type"] = "application/json";
      if (escribe) headers["X-XSRF-TOKEN"] = leerCookie("XSRF-TOKEN") || "";
      return fetch(ruta, {
        method,
        headers,
        credentials: "same-origin",
        body: body === undefined ? undefined : JSON.stringify(body),
      });
    };
    let respuesta;
    try {
      respuesta = await enviar();
      if (escribe && respuesta.status === 403) {
        const copia = respuesta.clone();
        const data = await copia.json().catch(() => null);
        if (data && String(data.mensaje || "").includes("CSRF")) {
          await pedirCsrf();
          respuesta = await enviar();
        }
      }
    } catch (e) {
      return { ok: false, status: 0, data: { mensaje: "No se pudo conectar con el servidor. Revisá que la app esté levantada." } };
    }
    const texto = await respuesta.text();
    let data = null;
    try {
      data = texto ? JSON.parse(texto) : null;
    } catch (e) {
      data = null;
    }
    return { ok: respuesta.ok, status: respuesta.status, data };
  };

  // --- Sesión ---

  /** usuario es el nombre de usuario o el email: el servidor busca por los dos. */
  const login = async (usuario, password) => {
    await pedirCsrf();
    const r = await api("/api/auth/login", { method: "POST", body: { usuario, password } });
    if (r.ok) sessionStorage.setItem(HUBO_SESION, "1");
    return r;
  };

  const elegirAmbito = (ambitoId) => api("/api/auth/ambito", { method: "POST", body: { ambitoId } });

  const me = () => api("/api/auth/me");

  const recuperar = (email) => api("/api/auth/recuperar", { method: "POST", body: { email } });

  const restablecer = (token, password) =>
    api("/api/auth/restablecer", { method: "POST", body: { token, password } });

  const logout = async () => {
    await api("/api/auth/logout", { method: "POST" });
    sessionStorage.removeItem(HUBO_SESION);
    window.location.href = LOGIN + "?motivo=salida";
  };

  /** A dónde va cada uno según la sesión: el administrador técnico, cada perfil, o a elegir ámbito. */
  const destino = (sesion) => {
    if (sesion.usuario.adminTecnico) return "admin.html";
    if (!sesion.ambitoActivo) return LOGIN + "?elegir=1";
    return paginaDelPerfil(sesion.ambitoActivo.perfil);
  };

  const paginaDelPerfil = (perfil) => (perfil === "dueno_fabrica" ? "fabrica.html" : "sucursal.html");

  /** Sin sesión: al login, avisando si es porque venció. */
  const alLogin = () => {
    const vencio = sessionStorage.getItem(HUBO_SESION) === "1";
    sessionStorage.removeItem(HUBO_SESION);
    window.location.replace(LOGIN + (vencio ? "?motivo=vencida" : ""));
  };

  // --- Protección de páginas internas ---

  let sesionActual = null;

  /**
   * Protege la página: perfiles es la lista de perfiles que la usan, o "admin" para el administrador técnico.
   * Si todo está bien, muestra la barra de sesión y resuelve con la sesión; si no, redirige y no resuelve.
   */
  const proteger = async (perfiles) => {
    const r = await me();
    if (r.status === 401) {
      alLogin();
      return new Promise(() => {});
    }
    if (!r.ok) {
      document.getElementById("root").innerHTML =
        '<p style="padding:24px;font-family:sans-serif">' + ((r.data && r.data.mensaje) || "No se pudo cargar la sesión.") + "</p>";
      return new Promise(() => {});
    }
    const sesion = r.data;
    sessionStorage.setItem(HUBO_SESION, "1");
    const corresponde =
      perfiles === "admin"
        ? sesion.usuario.adminTecnico
        : !sesion.usuario.adminTecnico && sesion.ambitoActivo && perfiles.includes(sesion.ambitoActivo.perfil);
    if (!corresponde) {
      window.location.replace(destino(sesion));
      return new Promise(() => {});
    }
    sesionActual = sesion;
    mostrarBarra(sesion);
    // Al volver a la pestaña, se revisa que la sesión siga vigente (vence a los 30 minutos sin actividad).
    document.addEventListener("visibilitychange", async () => {
      if (document.visibilityState !== "visible") return;
      const v = await me();
      if (v.status === 401) alLogin();
    });
    return sesion;
  };

  /** Barra fija con el ámbito y el perfil activos, y los botones de cambiar de ámbito y cerrar sesión. */
  const mostrarBarra = (sesion) => {
    const barra = document.createElement("div");
    barra.id = "gf-barra-sesion";
    barra.setAttribute("role", "region");
    barra.setAttribute("aria-label", "Sesión");
    barra.style.cssText =
      "position:sticky;top:0;z-index:60;display:flex;flex-wrap:wrap;align-items:center;gap:8px 16px;" +
      "justify-content:space-between;padding:8px 16px;background:#1A312B;color:#fff;" +
      "font:500 13px 'Plus Jakarta Sans',sans-serif;";
    const quien = sesion.usuario.adminTecnico
      ? "Administración técnica · Administrador técnico"
      : sesion.ambitoActivo.ambitoNombre + " · " + (PERFILES[sesion.ambitoActivo.perfil] || sesion.ambitoActivo.perfil);
    const izquierda = document.createElement("div");
    izquierda.innerHTML =
      '<span style="color:#00AE4C;font-weight:700">●</span> <span data-gf="ambito-perfil"></span>' +
      ' <span style="opacity:.6">· <span data-gf="usuario"></span></span>';
    izquierda.querySelector('[data-gf="ambito-perfil"]').textContent = quien;
    izquierda.querySelector('[data-gf="usuario"]').textContent = sesion.usuario.nombre + " · " + sesion.usuario.usuario;
    const derecha = document.createElement("div");
    derecha.style.cssText = "display:flex;gap:8px;";
    const boton = (texto, alHacerClick) => {
      const b = document.createElement("button");
      b.type = "button";
      b.textContent = texto;
      b.style.cssText =
        "padding:5px 12px;border-radius:999px;border:1px solid rgba(255,255,255,.3);background:transparent;" +
        "color:#fff;font:inherit;cursor:pointer;";
      b.addEventListener("click", alHacerClick);
      return b;
    };
    if (!sesion.usuario.adminTecnico && sesion.perfiles.length > 1) {
      derecha.appendChild(boton("Cambiar de ámbito", () => (window.location.href = LOGIN + "?elegir=1")));
    }
    derecha.appendChild(boton("Cerrar sesión", logout));
    barra.appendChild(izquierda);
    barra.appendChild(derecha);
    document.body.insertBefore(barra, document.body.firstChild);
  };

  // --- Puente con las pantallas de prueba ---

  /*
   * fabrica.html y sucursal.html todavía usan los datos de prueba de shared/db.js, con sus propios roles,
   * usuarios y sucursales. Hasta conectar cada pantalla a la API, la sesión real se traduce a ese formato: el
   * nombre y el ámbito son los reales; el rol, el usuario y la sucursal de prueba son los que esas pantallas
   * necesitan para mostrar sus datos de ejemplo.
   */
  const ROL_DE_PRUEBA = {
    dueno_fabrica: "administrativo_fabrica",
    dueno_local: "responsable_sucursal",
    empleado_local: "empleado_sucursal",
  };

  const sesionDePrueba = () => {
    if (!sesionActual || !sesionActual.ambitoActivo) return null;
    const rolNombre = ROL_DE_PRUEBA[sesionActual.ambitoActivo.perfil];
    const rol = window.GFDB && window.GFDB.query("roles", (r) => r.nombre === rolNombre)[0];
    const usuarioDePrueba = rol && window.GFDB.query("usuarios", (u) => u.rol_id === rol.id)[0];
    return {
      usuarioId: usuarioDePrueba ? usuarioDePrueba.id : null,
      nombre: sesionActual.usuario.nombre,
      rolId: rol ? rol.id : null,
      rolNombre,
      sucursalId: usuarioDePrueba ? usuarioDePrueba.sucursal_id : null,
      sucursalNombre: sesionActual.ambitoActivo.ambitoTipo === "local" ? sesionActual.ambitoActivo.ambitoNombre : null,
    };
  };

  /** Lo que usan las pantallas de prueba: la sesión ya validada por proteger(), en su formato. */
  const requireRole = () => {
    const sesion = sesionDePrueba();
    if (!sesion) alLogin();
    return sesion;
  };

  const getSession = sesionDePrueba;

  /** Arranca una página interna: primero valida la sesión y después ejecuta su código (Babel). */
  const arrancar = (perfiles, ejecutar) => {
    proteger(perfiles).then(ejecutar);
  };

  window.GFAuth = {
    api,
    login,
    elegirAmbito,
    me,
    recuperar,
    restablecer,
    logout,
    destino,
    proteger,
    arrancar,
    requireRole,
    getSession,
    PERFILES,
    TIPOS_AMBITO,
  };
})();
