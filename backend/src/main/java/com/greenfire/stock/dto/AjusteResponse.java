package com.greenfire.stock.dto;

import java.math.BigDecimal;
import java.util.List;

/** Los movimientos que generó el ajuste, con la cantidad que quedó en cada artículo y ubicación. */
public record AjusteResponse(List<Renglon> renglones) {

    public record Renglon(Long movimientoId, Long articuloId, Long ubicacionId, BigDecimal cantidad,
            BigDecimal cantidadResultante) {
    }
}
