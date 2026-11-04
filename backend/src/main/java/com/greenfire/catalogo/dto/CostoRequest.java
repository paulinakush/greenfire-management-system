package com.greenfire.catalogo.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

public record CostoRequest(@NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal costo) {
}
