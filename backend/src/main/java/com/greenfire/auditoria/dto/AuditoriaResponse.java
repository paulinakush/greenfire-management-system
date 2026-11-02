package com.greenfire.auditoria.dto;

import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonRawValue;
import com.greenfire.auditoria.Auditoria;
import com.greenfire.seguridad.Perfil;

public record AuditoriaResponse(
        Long id,
        OffsetDateTime fechaHora,
        Long usuarioId,
        String usuarioNombre,
        Perfil perfil,
        Long ambitoId,
        String ambitoNombre,
        String accion,
        String entidad,
        Long entidadId,
        @JsonRawValue String detalle,
        String emailIntentado,
        String ip) {

    public static AuditoriaResponse de(Auditoria a) {
        return new AuditoriaResponse(a.getId(), a.getFechaHora(),
                a.getUsuario() == null ? null : a.getUsuario().getId(),
                a.getUsuario() == null ? null : a.getUsuario().getNombre(),
                a.getPerfil(),
                a.getAmbito() == null ? null : a.getAmbito().getId(),
                a.getAmbito() == null ? null : a.getAmbito().getNombre(),
                a.getAccion(), a.getEntidad(), a.getEntidadId(), a.getDetalle(), a.getEmailIntentado(), a.getIp());
    }
}
