package com.greenfire.seguridad;

import java.util.Set;

import org.springframework.stereotype.Component;

import com.greenfire.comun.AccesoDenegadoException;
import com.greenfire.comun.NoAutenticadoException;

/**
 * Validaciones de acceso que usan los servicios. El ámbito siempre sale de la sesión,
 * nunca de un dato que mande el front.
 */
@Component
public class Acceso {

    private final SesionActual sesionActual;

    public Acceso(SesionActual sesionActual) {
        this.sesionActual = sesionActual;
    }

    public Sesion sesion() {
        return sesionActual.obtener().orElseThrow(NoAutenticadoException::new);
    }

    public Sesion exigirAdminTecnico() {
        Sesion sesion = sesion();
        if (!sesion.adminTecnico()) {
            throw new AccesoDenegadoException();
        }
        return sesion;
    }

    /** Exige estar trabajando en un ámbito con alguno de los perfiles indicados. */
    public Sesion exigirPerfil(Perfil... perfiles) {
        Sesion sesion = sesion();
        if (sesion.ambitoId() == null || !Set.of(perfiles).contains(sesion.perfil())) {
            throw new AccesoDenegadoException();
        }
        return sesion;
    }

    /** Exige estar trabajando en algún ámbito, con cualquier perfil. */
    public Sesion exigirAmbito() {
        return exigirPerfil(Perfil.values());
    }
}
