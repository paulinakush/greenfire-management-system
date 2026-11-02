package com.greenfire.auditoria;

import java.util.Collection;
import java.util.List;
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
 *
 * Toda alta, edición o desactivación que toque a un ámbito lo indica como afectado, para que el dueño de ese
 * ámbito la vea aunque la haya hecho otro (por ejemplo, el administrador técnico).
 */
@Component
public class Auditor {

    /** Acciones de login y sesión: solo las ve el administrador técnico. */
    public static final List<String> ACCIONES_DE_ACCESO = List.of("login", "login_fallido", "logout",
            "usuario_bloqueado", "cambio_ambito", "recuperacion_solicitada", "password_restablecida",
            "crear_admin_inicial");

    private final ApplicationEventPublisher eventos;
    private final SesionActual sesionActual;

    public Auditor(ApplicationEventPublisher eventos, SesionActual sesionActual) {
        this.eventos = eventos;
        this.sesionActual = sesionActual;
    }

    /** Operación que no toca a ningún ámbito (por ejemplo, el alta de una entidad fiscal todavía sin locales). */
    public void registrar(String accion, String entidad, Long entidadId, Map<String, ?> detalle) {
        registrar(accion, entidad, entidadId, List.of(), detalle);
    }

    /** Si toca varios ámbitos, queda un registro por cada uno. */
    public void registrar(String accion, String entidad, Long entidadId, Collection<Long> ambitosAfectados,
            Map<String, ?> detalle) {
        Optional<Sesion> sesion = sesionActual.obtener();
        publicar(accion, entidad, entidadId, ambitosAfectados, detalle, sesion.map(Sesion::usuarioId).orElse(null),
                null);
    }

    /** Registra una edición solo si algún campo cambió. */
    public void registrarCambios(String accion, String entidad, Long entidadId, Collection<Long> ambitosAfectados,
            Cambios cambios) {
        if (!cambios.vacio()) {
            registrar(accion, entidad, entidadId, ambitosAfectados, cambios.comoMapa());
        }
    }

    /**
     * Para eventos de acceso (login, logout, bloqueo, recuperación), donde el usuario todavía no está en la
     * sesión o no se sabe si existe. No tocan a ningún ámbito y los dueños no los ven (ver
     * {@link #ACCIONES_DE_ACCESO}).
     */
    public void registrarAcceso(String accion, Long usuarioId, String emailIntentado, Map<String, ?> detalle) {
        publicar(accion, "usuario", usuarioId, List.of(), detalle, usuarioId, emailIntentado);
    }

    private void publicar(String accion, String entidad, Long entidadId, Collection<Long> ambitosAfectados,
            Map<String, ?> detalle, Long usuarioId, String emailIntentado) {
        Optional<Sesion> sesion = sesionActual.obtener();
        eventos.publishEvent(new EventoAuditoria(accion, entidad, entidadId,
                ambitosAfectados.stream().distinct().toList(), detalle == null ? Map.of() : detalle,
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
