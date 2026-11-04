package com.greenfire.stock.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

/** Mínimo y máximo de un artículo en una ubicación; cualquiera de los dos puede quedar sin valor. */
public record LimitesRequest(
        @NotNull Long articuloId,
        @NotNull Long ubicacionId,
        @DecimalMin("0") @Digits(integer = 11, fraction = 3) BigDecimal stockMinimo,
        @DecimalMin("0") @Digits(integer = 11, fraction = 3) BigDecimal stockMaximo) {

    @AssertTrue(message = "el máximo no puede ser menor que el mínimo")
    public boolean isMaximoNoMenorQueMinimo() {
        return stockMinimo == null || stockMaximo == null || stockMaximo.compareTo(stockMinimo) >= 0;
    }
}
