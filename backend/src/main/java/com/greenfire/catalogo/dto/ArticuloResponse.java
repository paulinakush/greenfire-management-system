package com.greenfire.catalogo.dto;

import java.math.BigDecimal;

import com.greenfire.catalogo.Articulo;
import com.greenfire.catalogo.TipoArticulo;
import com.greenfire.catalogo.UnidadMedida;

/**
 * Artículo visto por un dueño. El costo es el de su ámbito (null si no tiene) y {@code editable} indica si el
 * artículo es de su ámbito.
 */
public record ArticuloResponse(
        Long id,
        TipoArticulo tipo,
        String nombre,
        String descripcion,
        Long categoriaId,
        String categoriaNombre,
        String marca,
        UnidadMedida unidadMedida,
        String imagen,
        BigDecimal alicuotaIva,
        boolean visibleWeb,
        boolean activo,
        boolean editable,
        BigDecimal costo) implements ArticuloVista {

    public static ArticuloResponse de(Articulo a, Long ambitoId, BigDecimal costo) {
        return new ArticuloResponse(a.getId(), a.getTipo(), a.getNombre(), a.getDescripcion(),
                a.getCategoria() == null ? null : a.getCategoria().getId(),
                a.getCategoria() == null ? null : a.getCategoria().getNombre(),
                a.getMarca(), a.getUnidadMedida(), a.getImagen(), a.getAlicuotaIva(), a.isVisibleWeb(),
                a.isActivo(), a.getAmbito().getId().equals(ambitoId), costo);
    }
}
