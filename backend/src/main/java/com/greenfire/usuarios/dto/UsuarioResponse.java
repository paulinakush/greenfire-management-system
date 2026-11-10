package com.greenfire.usuarios.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.greenfire.ambitos.TipoAmbito;
import com.greenfire.seguridad.Perfil;
import com.greenfire.usuarios.Usuario;
import com.greenfire.usuarios.UsuarioAmbito;

/** Usuario con todos sus perfiles, para el administrador técnico. */
public record UsuarioResponse(Long id, String nombre, String usuario, String email, boolean activo, boolean bloqueado,
        OffsetDateTime ultimoAcceso, List<PerfilResponse> perfiles) {

    public record PerfilResponse(Long ambitoId, String ambitoNombre, TipoAmbito ambitoTipo, Perfil perfil,
            boolean activo) {
    }

    public static UsuarioResponse de(Usuario u, List<UsuarioAmbito> perfiles) {
        return new UsuarioResponse(u.getId(), u.getNombre(), u.getUsuario(), u.getEmail(), u.isActivo(),
                u.estaBloqueado(OffsetDateTime.now()), u.getUltimoAcceso(),
                perfiles.stream().map(ua -> new PerfilResponse(ua.getAmbito().getId(), ua.getAmbito().getNombre(),
                        ua.getAmbito().getTipo(), ua.getPerfil(), ua.isActivo())).toList());
    }
}
