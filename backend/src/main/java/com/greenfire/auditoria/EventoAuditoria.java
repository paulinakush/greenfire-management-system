package com.greenfire.auditoria;

import java.util.List;
import java.util.Map;

import com.greenfire.seguridad.Perfil;

/**
 * Operación que debe quedar en auditoría, con quién la hizo, con qué perfil, en qué ámbito y desde qué IP.
 *
 * @param ambitosAfectados ámbitos que toca la operación; se guarda un registro por cada uno
 * @param detalle datos de la operación; en una edición, cada campo con su valor anterior y el nuevo
 * @param emailIntentado usuario o email escrito en un login fallido, aunque no corresponda a ningún usuario
 */
public record EventoAuditoria(
        String accion,
        String entidad,
        Long entidadId,
        List<Long> ambitosAfectados,
        Map<String, ?> detalle,
        Long usuarioId,
        Perfil perfil,
        Long ambitoId,
        String emailIntentado,
        String ip) {
}
