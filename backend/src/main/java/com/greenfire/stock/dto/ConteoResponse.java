package com.greenfire.stock.dto;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import com.greenfire.catalogo.UnidadMedida;
import com.greenfire.stock.DetalleConteo;

/**
 * Un conteo con su detalle. Mientras está abierto no muestra la cantidad del sistema (se cuenta sin verla) y la
 * diferencia es null; las dos aparecen al cerrar.
 */
public record ConteoResponse(@JsonUnwrapped ConteoResumenResponse conteo, List<Item> items) {

    public record Item(Long articuloId, String articuloNombre, UnidadMedida unidadMedida, BigDecimal cantidadContada,
            BigDecimal cantidadSistema, BigDecimal diferencia) {

        public static Item de(DetalleConteo d) {
            return new Item(d.getArticulo().getId(), d.getArticulo().getNombre(), d.getArticulo().getUnidadMedida(),
                    d.getCantidadContada(), d.getCantidadSistema(), d.diferencia());
        }
    }
}
