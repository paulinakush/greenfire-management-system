package com.greenfire.stock.dto;

import java.math.BigDecimal;

import com.greenfire.catalogo.TipoArticulo;
import com.greenfire.catalogo.UnidadMedida;

/**
 * Stock de un artículo en una ubicación. No tiene costos, así que es el mismo para dueños y empleados.
 * {@code bajoMinimo} es true si hay mínimo y la cantidad está por debajo.
 */
public record StockResponse(
        Long articuloId,
        String articuloNombre,
        TipoArticulo tipo,
        UnidadMedida unidadMedida,
        Long ubicacionId,
        String ubicacionNombre,
        BigDecimal cantidad,
        BigDecimal stockMinimo,
        BigDecimal stockMaximo,
        boolean bajoMinimo) {

    public static boolean bajoMinimo(BigDecimal cantidad, BigDecimal minimo) {
        return minimo != null && cantidad.compareTo(minimo) < 0;
    }
}
