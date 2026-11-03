package com.greenfire.precios.dto;

import java.math.BigDecimal;

import com.greenfire.catalogo.TipoArticulo;

/**
 * Precio de un artículo en una lista, para el dueño. {@code precioNeto} es el precio sin IVA si la entidad
 * discrimina IVA, y el precio final si no. {@code costo} es el del ámbito de la lista; si no hay costo, el margen
 * es null. El margen es una fracción: 0.25 es 25 %.
 */
public record ListaPreciosItemResponse(
        Long articuloId,
        String articuloNombre,
        TipoArticulo tipo,
        Long categoriaId,
        BigDecimal alicuotaIva,
        boolean articuloActivo,
        BigDecimal precio,
        BigDecimal precioNeto,
        BigDecimal costo,
        BigDecimal margen) {
}
