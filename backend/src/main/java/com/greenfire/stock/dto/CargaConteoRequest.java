package com.greenfire.stock.dto;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Cantidades contadas. Se puede cargar de a partes: cada carga agrega o reemplaza los artículos que trae y deja
 * los demás como estaban. {@code cantidadContada} null saca el artículo del conteo.
 */
public record CargaConteoRequest(@NotEmpty @Size(max = 500) List<@Valid @NotNull Item> items) {

    public record Item(
            @NotNull Long articuloId,
            @DecimalMin("0") @Digits(integer = 11, fraction = 3) BigDecimal cantidadContada) {
    }
}
