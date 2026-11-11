package com.greenfire.caja.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import com.greenfire.caja.MedioPago;
import com.greenfire.caja.MovimientoCaja;
import com.greenfire.caja.OrigenMovimientoCaja;
import com.greenfire.caja.RubroCaja;
import com.greenfire.caja.TipoMovimientoCaja;

public record MovimientoCajaResponse(
        Long id,
        OrigenMovimientoCaja origen,
        TipoMovimientoCaja tipo,
        RubroCaja rubro,
        MedioPago medioPago,
        BigDecimal monto,
        OffsetDateTime fechaHora,
        String usuario,
        String observacion,
        Long corrigeMovimientoId,
        Long ventaId) {

    public static MovimientoCajaResponse de(MovimientoCaja m) {
        return new MovimientoCajaResponse(m.getId(), m.getOrigen(), m.getTipo(), m.getRubro(), m.getMedioPago(),
                m.getMonto(), m.getFechaHora(), m.getUsuario().getNombre(), m.getObservacion(),
                m.getCorrige() == null ? null : m.getCorrige().getId(), m.getVentaId());
    }
}
