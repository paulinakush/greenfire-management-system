package com.greenfire.seguridad.dto;

import java.util.List;

import com.greenfire.ambitos.TipoAmbito;
import com.greenfire.seguridad.Perfil;

/** Quién está logueado, en qué ámbitos tiene perfil y en cuál está trabajando (null si todavía no eligió). */
public record SesionResponse(UsuarioSesion usuario, List<PerfilSesion> perfiles, PerfilSesion ambitoActivo) {

    public record UsuarioSesion(Long id, String nombre, String usuario, String email, boolean adminTecnico) {
    }

    public record PerfilSesion(Long ambitoId, String ambitoNombre, TipoAmbito ambitoTipo, Perfil perfil) {
    }
}
