package com.greenfire.caja.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import com.greenfire.caja.EstadoCaja;
import com.greenfire.caja.MovimientoCaja;
import com.greenfire.caja.OrigenMovimientoCaja;
import com.greenfire.caja.RubroCaja;
import com.greenfire.caja.SesionCaja;

/**
 * La sesión para el empleado: caja a ciegas. Mientras está abierta no trae totales ni el efectivo esperado, así
 * cuenta el efectivo sin saber cuánto debería haber. Recién cerrada trae el cierre, con el esperado y la
 * diferencia. Muestra los movimientos manuales, salvo los pagos a la fábrica (regla 2 de CLAUDE.md).
 */
public record SesionCajaEmpleadoResponse(
        Long id,
        EstadoCaja estado,
        OffsetDateTime abiertaAt,
        String usuarioApertura,
        BigDecimal saldoInicial,
        List<MovimientoCajaResponse> movimientos,
        Cierre cierre) implements SesionCajaVista {

    public record Cierre(
            OffsetDateTime cerradaAt,
            String usuarioCierre,
            BigDecimal totalEfectivo,
            BigDecimal totalTransferencia,
            BigDecimal totalBilletera,
            BigDecimal totalGeneral,
            BigDecimal ingresosEfectivo,
            BigDecimal egresosEfectivo,
            BigDecimal efectivoEsperado,
            BigDecimal efectivoContado,
            BigDecimal diferencia,
            String observaciones) {
    }

    public static SesionCajaEmpleadoResponse de(SesionCaja s, List<MovimientoCaja> movimientos) {
        Cierre cierre = s.estaAbierta() ? null
                : new Cierre(s.getCerradaAt(), s.getUsuarioCierre().getNombre(), s.getTotalEfectivo(),
                        s.getTotalTransferencia(), s.getTotalBilletera(), s.getTotalGeneral(), s.getIngresosEfectivo(),
                        s.getEgresosEfectivo(), s.getEfectivoEsperado(), s.getEfectivoContado(), s.getDiferencia(),
                        s.getObservaciones());
        return new SesionCajaEmpleadoResponse(s.getId(), s.getEstado(), s.getAbiertaAt(),
                s.getUsuarioApertura().getNombre(), s.getSaldoInicial(),
                movimientos.stream()
                        .filter(m -> m.getOrigen() == OrigenMovimientoCaja.manual && m.getRubro() != RubroCaja.pago_fabrica)
                        .map(MovimientoCajaResponse::de).toList(),
                cierre);
    }
}
