package com.greenfire.precios.dto;

import com.greenfire.precios.ListaPrecios;

/** {@code discriminaIva} indica si la entidad del ámbito discrimina IVA; si no, el precio es el precio final. */
public record ListaPreciosResponse(Long id, String nombre, boolean preciosConIva, boolean esPredeterminada,
        boolean activo, boolean discriminaIva) {

    public static ListaPreciosResponse de(ListaPrecios l, boolean discriminaIva) {
        return new ListaPreciosResponse(l.getId(), l.getNombre(), l.isPreciosConIva(), l.isEsPredeterminada(),
                l.isActivo(), discriminaIva);
    }
}
