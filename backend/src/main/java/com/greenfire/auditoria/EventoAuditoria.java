package com.greenfire.auditoria;

import java.util.Map;

import com.greenfire.seguridad.Perfil;

/**
 * Operación que debe quedar en auditoría, con quién la hizo, con qué perfil, en qué ámbito y desde qué IP.
 *
 * @param detalle datos de la operación; en una edición, cada campo con su valor anterior y el nuevo
 * @param emailIntentado email usado en un login fallido, aunque no corresponda a ningún usuario
 */
public record EventoAuditoria(
        String accion,
        String entidad,
        Long entidadId,
        Map<String, ?> detalle,
        Long usuarioId,
        Perfil perfil,
        Long ambitoId,
        String emailIntentado,
        String ip) {
}
