package com.greenfire.caja.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import com.greenfire.caja.CierreCaja;
import com.greenfire.caja.EstadoCaja;
import com.greenfire.caja.MovimientoCaja;
import com.greenfire.caja.SesionCaja;

/**
 * La sesión completa, para el dueño del local: los totales por medio y el efectivo esperado (hasta el momento si
 * sigue abierta), lo contado, la diferencia, la corrección, si quedó olvidada y todos los movimientos.
 */
public record SesionCajaResponse(
        Long id,
        Long ambitoId,
        EstadoCaja estado,
        boolean olvidada,
        OffsetDateTime abiertaAt,
        String usuarioApertura,
        OffsetDateTime cerradaAt,
        String usuarioCierre,
        BigDecimal saldoInicial,
        CierreCaja.Totales totales,
        BigDecimal efectivoContado,
        BigDecimal diferencia,
        String observaciones,
        Correccion correccion,
        List<MovimientoCajaResponse> movimientos) implements SesionCajaVista {

    public record Correccion(OffsetDateTime fecha, String usuario, String motivo) {
    }

    public static SesionCajaResponse de(SesionCaja s, List<MovimientoCaja> movimientos, OffsetDateTime ahora) {
        return new SesionCajaResponse(s.getId(), s.getAmbito().getId(), s.getEstado(), s.olvidada(ahora),
                s.getAbiertaAt(), s.getUsuarioApertura().getNombre(), s.getCerradaAt(),
                s.getUsuarioCierre() == null ? null : s.getUsuarioCierre().getNombre(), s.getSaldoInicial(),
                CierreCaja.calcular(s.getSaldoInicial(), movimientos), s.getEfectivoContado(), s.getDiferencia(),
                s.getObservaciones(),
                s.getCorregidaAt() == null ? null
                        : new Correccion(s.getCorregidaAt(), s.getCorregidaPor().getNombre(), s.getMotivoCorreccion()),
                movimientos.stream().map(MovimientoCajaResponse::de).toList());
    }
}
