package com.greenfire.catalogo.dto;

import java.math.BigDecimal;

import com.greenfire.catalogo.Articulo;
import com.greenfire.catalogo.TipoArticulo;
import com.greenfire.catalogo.UnidadMedida;

/** Artículo visto por el empleado: sin costo. */
public record ArticuloEmpleadoResponse(
        Long id,
        TipoArticulo tipo,
        String nombre,
        String descripcion,
        Long categoriaId,
        String categoriaNombre,
        String marca,
        UnidadMedida unidadMedida,
        String imagen,
        BigDecimal alicuotaIva) implements ArticuloVista {

    public static ArticuloEmpleadoResponse de(Articulo a) {
        return new ArticuloEmpleadoResponse(a.getId(), a.getTipo(), a.getNombre(), a.getDescripcion(),
                a.getCategoria() == null ? null : a.getCategoria().getId(),
                a.getCategoria() == null ? null : a.getCategoria().getNombre(),
                a.getMarca(), a.getUnidadMedida(), a.getImagen(), a.getAlicuotaIva());
    }
}
