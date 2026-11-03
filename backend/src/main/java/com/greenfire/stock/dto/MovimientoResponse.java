package com.greenfire.stock.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import com.greenfire.stock.MovimientoStock;
import com.greenfire.stock.TipoMovimientoStock;

public record MovimientoResponse(
        Long id,
        OffsetDateTime fechaHora,
        TipoMovimientoStock tipo,
        Long articuloId,
        String articuloNombre,
        Long ubicacionId,
        String ubicacionNombre,
        BigDecimal cantidad,
        String motivo,
        String observaciones,
        Long loteId,
        Long usuarioId,
        String usuarioNombre) {

    public static MovimientoResponse de(MovimientoStock m) {
        return new MovimientoResponse(m.getId(), m.getFechaHora(), m.getTipo(), m.getArticulo().getId(),
                m.getArticulo().getNombre(), m.getUbicacion().getId(), m.getUbicacion().getNombre(), m.getCantidad(),
                m.getMotivo(), m.getObservaciones(), m.getLoteId(), m.getUsuario().getId(),
                m.getUsuario().getNombre());
    }
}
