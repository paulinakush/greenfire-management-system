/* ---------------------------------------------------------
   AUTH — login único para las interfaces privadas de Green Fire.

   Sesión en sessionStorage (se cierra sola al cerrar la pestaña).
   El password es texto plano en el seed de GFDB: es un mock de
   prototipo sin backend, no una implementación de seguridad real.
--------------------------------------------------------- */

(function () {
  const SESSION_KEY = "gf_session";

  const rutaPorRol = (rolNombre) => {
    if (rolNombre === "administrativo_fabrica") return "fabrica.html";
    return "sucursal.html";
  };

  const login = (email, password) => {
    const usuario = window.GFDB.query(
      "usuarios",
      (u) => u.email.toLowerCase() === String(email).toLowerCase() && u.password === password
    )[0];
    if (!usuario) return { ok: false, error: "Email o contraseña incorrectos." };
    if (!usuario.activo) return { ok: false, error: "Este usuario está deshabilitado." };
    const rol = window.GFDB.find("roles", usuario.rol_id);
    const sucursal = usuario.sucursal_id ? window.GFDB.find("sucursales", usuario.sucursal_id) : null;
    const session = {
      usuarioId: usuario.id,
      nombre: usuario.nombre,
      rolId: rol.id,
      rolNombre: rol.nombre,
      sucursalId: usuario.sucursal_id,
      sucursalNombre: sucursal ? sucursal.nombre : null,
    };
    sessionStorage.setItem(SESSION_KEY, JSON.stringify(session));
    return { ok: true, session };
  };

  const getSession = () => {
    try {
      const raw = sessionStorage.getItem(SESSION_KEY);
      return raw ? JSON.parse(raw) : null;
    } catch (e) {
      return null;
    }
  };

  const logout = () => {
    sessionStorage.removeItem(SESSION_KEY);
    window.location.href = "login.html";
  };

  // Redirige a login si no hay sesión o el rol no corresponde a esta
  // página; si la sesión es válida pero de la otra app, la manda a la
  // que sí le corresponde en lugar de dejarla en una pantalla vacía.
  const requireRole = (allowedRoles) => {
    const session = getSession();
    if (!session) {
      window.location.href = "login.html";
      return null;
    }
    if (!allowedRoles.includes(session.rolNombre)) {
      window.location.href = rutaPorRol(session.rolNombre);
      return null;
    }
    return session;
  };

  window.GFAuth = { login, getSession, logout, requireRole, rutaPorRol };
})();
