package com.greenfire.caja.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Cierre de la caja abierta con el efectivo que se contó. */
public record CierreRequest(
        @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal efectivoContado,
        @Size(max = 1000) String observaciones) {
}
