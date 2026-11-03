package com.greenfire.catalogo.dto;

import java.math.BigDecimal;

import com.greenfire.catalogo.Articulo;
import com.greenfire.catalogo.UnidadMedida;

/**
 * Artículo del catálogo público, con el precio al público del local elegido. Sin costos, márgenes, cantidades,
 * ubicaciones ni datos fiscales: la disponibilidad es solo sí o no. Un test compara sus claves contra una lista
 * permitida, así un campo nuevo no se publica sin querer.
 */
public record ArticuloPublicoResponse(
        Long id,
        String nombre,
        String descripcion,
        Long categoriaId,
        String categoriaNombre,
        String marca,
        UnidadMedida unidadMedida,
        String imagen,
        BigDecimal precio,
        boolean disponible) {

    public static ArticuloPublicoResponse de(Articulo a, BigDecimal precio, boolean disponible) {
        return new ArticuloPublicoResponse(a.getId(), a.getNombre(), a.getDescripcion(), a.getCategoria().getId(),
                a.getCategoria().getNombre(), a.getMarca(), a.getUnidadMedida(), a.getImagen(), precio, disponible);
    }
}
