package com.greenfire.caja.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Corrección autorizada de un cierre: solo el efectivo contado, con motivo. */
public record CorreccionRequest(
        @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal efectivoContado,
        @NotBlank @Size(max = 500) String motivo) {
}
