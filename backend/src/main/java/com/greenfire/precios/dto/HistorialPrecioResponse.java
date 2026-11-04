package com.greenfire.precios.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import com.greenfire.precios.HistorialPrecio;

public record HistorialPrecioResponse(Long articuloId, String articuloNombre, BigDecimal precioAnterior,
        BigDecimal precioNuevo, Long usuarioId, String usuarioNombre, OffsetDateTime fechaHora) {

    public static HistorialPrecioResponse de(HistorialPrecio h) {
        return new HistorialPrecioResponse(h.getArticulo().getId(), h.getArticulo().getNombre(),
                h.getPrecioAnterior(), h.getPrecioNuevo(), h.getUsuario().getId(), h.getUsuario().getNombre(),
                h.getFechaHora());
    }
}
