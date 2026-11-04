package com.greenfire.precios.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

public record PrecioRequest(
        @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 12, fraction = 2) BigDecimal precio) {
}
