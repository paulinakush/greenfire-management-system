package com.greenfire.ambitos.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

/** Condiciones de venta del local que fija su dueño: el descuento máximo del empleado, en porcentaje. */
public record CondicionesVentaRequest(
        @NotNull @DecimalMin("0") @DecimalMax("100") @Digits(integer = 3, fraction = 2)
        BigDecimal descuentoMaximoEmpleado) {
}
