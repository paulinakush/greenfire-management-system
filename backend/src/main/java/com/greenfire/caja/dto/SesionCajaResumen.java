package com.greenfire.caja.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import com.greenfire.caja.EstadoCaja;
import com.greenfire.caja.SesionCaja;

/** Una sesión en el historial del dueño, sin movimientos. Los importes del cierre son null mientras está abierta. */
public record SesionCajaResumen(
        Long id,
        EstadoCaja estado,
        boolean olvidada,
        OffsetDateTime abiertaAt,
        String usuarioApertura,
        OffsetDateTime cerradaAt,
        String usuarioCierre,
        BigDecimal saldoInicial,
        BigDecimal totalGeneral,
        BigDecimal efectivoEsperado,
        BigDecimal efectivoContado,
        BigDecimal diferencia,
        boolean corregida) {

    public static SesionCajaResumen de(SesionCaja s, OffsetDateTime ahora) {
        return new SesionCajaResumen(s.getId(), s.getEstado(), s.olvidada(ahora), s.getAbiertaAt(),
                s.getUsuarioApertura().getNombre(), s.getCerradaAt(),
                s.getUsuarioCierre() == null ? null : s.getUsuarioCierre().getNombre(), s.getSaldoInicial(),
                s.getTotalGeneral(), s.getEfectivoEsperado(), s.getEfectivoContado(), s.getDiferencia(),
                s.getCorregidaAt() != null);
    }
}
