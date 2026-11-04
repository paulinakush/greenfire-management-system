package com.greenfire.fiscal.dto;

import com.greenfire.precios.ListaPrecios;

/** Una lista de precios de la entidad, sin sus precios. */
public record ListaPreciosAfectada(Long id, String nombre, Long ambitoId, String ambitoNombre, boolean preciosConIva) {

    public static ListaPreciosAfectada de(ListaPrecios l) {
        return new ListaPreciosAfectada(l.getId(), l.getNombre(), l.getAmbito().getId(), l.getAmbito().getNombre(),
                l.isPreciosConIva());
    }
}
