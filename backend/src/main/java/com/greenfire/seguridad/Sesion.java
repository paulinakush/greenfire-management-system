package com.greenfire.seguridad;

import java.io.Serializable;

/**
 * Lo que la sesión HTTP sabe del usuario: quién es y en qué ámbito y con qué perfil está trabajando.
 * El administrador técnico no tiene ámbito ni perfil. Un usuario con perfiles en varios ámbitos
 * queda sin ámbito hasta que elige uno.
 */
public record Sesion(Long usuarioId, boolean adminTecnico, Long ambitoId, Perfil perfil) implements Serializable {

    public static final String ATRIBUTO = "GF_SESION";

    public static Sesion adminTecnico(Long usuarioId) {
        return new Sesion(usuarioId, true, null, null);
    }

    public static Sesion sinAmbito(Long usuarioId) {
        return new Sesion(usuarioId, false, null, null);
    }

    public static Sesion enAmbito(Long usuarioId, Long ambitoId, Perfil perfil) {
        return new Sesion(usuarioId, false, ambitoId, perfil);
    }
}
