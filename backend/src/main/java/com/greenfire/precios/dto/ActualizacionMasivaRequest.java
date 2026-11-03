package com.greenfire.precios.dto;

import java.math.BigDecimal;

import com.greenfire.catalogo.TipoArticulo;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

/**
 * Cambia en un porcentaje los precios de una lista, opcionalmente solo los de una categoría o un tipo. Cada
 * precio nuevo se redondea al múltiplo de {@code redondeo} más cercano (por ejemplo 1, 10 o 50); sin redondeo,
 * al centavo.
 */
public record ActualizacionMasivaRequest(
        @NotNull @DecimalMin(value = "-100", inclusive = false) @DecimalMax("1000") @Digits(integer = 4, fraction = 2)
        BigDecimal porcentaje,
        Long categoriaId,
        TipoArticulo tipo,
        @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal redondeo) {
}
