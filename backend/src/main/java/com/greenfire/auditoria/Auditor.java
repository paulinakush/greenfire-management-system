package com.greenfire.auditoria;

import java.util.Map;
import java.util.Optional;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.greenfire.comun.Cambios;
import com.greenfire.seguridad.Sesion;
import com.greenfire.seguridad.SesionActual;

/**
 * Punto único desde el que se registran operaciones auditables. Completa usuario, perfil y ámbito
 * con la sesión actual, y la IP con el request en curso.
 */
@Component
public class Auditor {

    private final ApplicationEventPublisher eventos;
    private final SesionActual sesionActual;

    public Auditor(ApplicationEventPublisher eventos, SesionActual sesionActual) {
        this.eventos = eventos;
        this.sesionActual = sesionActual;
    }

    public void registrar(String accion, String entidad, Long entidadId, Map<String, ?> detalle) {
        Optional<Sesion> sesion = sesionActual.obtener();
        publicar(accion, entidad, entidadId, detalle, sesion.map(Sesion::usuarioId).orElse(null), null);
    }

    /** Registra una edición solo si algún campo cambió. */
    public void registrarCambios(String accion, String entidad, Long entidadId, Cambios cambios) {
        if (!cambios.vacio()) {
            registrar(accion, entidad, entidadId, cambios.comoMapa());
        }
    }

    /**
     * Para eventos de acceso (login, bloqueo, recuperación), donde el usuario todavía no está en la sesión
     * o no se sabe si existe.
     */
    public void registrarAcceso(String accion, Long usuarioId, String emailIntentado, Map<String, ?> detalle) {
        publicar(accion, "usuario", usuarioId, detalle, usuarioId, emailIntentado);
    }

    private void publicar(String accion, String entidad, Long entidadId, Map<String, ?> detalle, Long usuarioId,
            String emailIntentado) {
        Optional<Sesion> sesion = sesionActual.obtener();
        eventos.publishEvent(new EventoAuditoria(accion, entidad, entidadId, detalle == null ? Map.of() : detalle,
                usuarioId,
                sesion.map(Sesion::perfil).orElse(null),
                sesion.map(Sesion::ambitoId).orElse(null),
                emailIntentado,
                ip()));
    }

    private static String ip() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes request
                ? request.getRequest().getRemoteAddr()
                : null;
    }
}
