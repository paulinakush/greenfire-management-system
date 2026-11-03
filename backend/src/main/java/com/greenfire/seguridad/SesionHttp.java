package com.greenfire.seguridad;

import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;

/** Abre, actualiza y cierra la sesión HTTP (guardada en la base con Spring Session). */
@Component
public class SesionHttp {

    private final FindByIndexNameSessionRepository<? extends Session> sesiones;

    public SesionHttp(FindByIndexNameSessionRepository<? extends Session> sesiones) {
        this.sesiones = sesiones;
    }

    /** Abre la sesión con un id nuevo, para que no se pueda reutilizar uno anterior al login. */
    public void iniciar(HttpServletRequest request, Sesion sesion) {
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        HttpSession http = request.getSession(true);
        http.setAttribute(Sesion.ATRIBUTO, sesion);
        // Permite encontrar y cerrar todas las sesiones de un usuario.
        http.setAttribute(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME, sesion.usuarioId().toString());
    }

    public void actualizar(HttpServletRequest request, Sesion sesion) {
        request.getSession(true).setAttribute(Sesion.ATRIBUTO, sesion);
    }

    public void cerrar(HttpServletRequest request) {
        HttpSession http = request.getSession(false);
        if (http != null) {
            http.invalidate();
        }
    }

    /** Cierra todas las sesiones abiertas del usuario, en cualquier navegador. */
    public void cerrarTodas(Long usuarioId) {
        sesiones.findByPrincipalName(usuarioId.toString()).keySet().forEach(sesiones::deleteById);
    }
}
