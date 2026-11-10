package com.greenfire.usuarios.dto;

import com.greenfire.seguridad.Perfil;
import com.greenfire.usuarios.UsuarioAmbito;

/** Usuario de un local tal como lo ve su dueño: solo su perfil en ese local. */
public record UsuarioLocalResponse(Long id, String nombre, String usuario, String email, Perfil perfil, boolean activo) {

    public static UsuarioLocalResponse de(UsuarioAmbito ua) {
        return new UsuarioLocalResponse(ua.getUsuario().getId(), ua.getUsuario().getNombre(),
                ua.getUsuario().getUsuario(), ua.getUsuario().getEmail(), ua.getPerfil(), ua.isActivo() && ua.getUsuario().isActivo());
    }
}
