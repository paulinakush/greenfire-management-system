package com.greenfire.caja.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

/** Apertura de la caja del local, con el efectivo con el que arranca. */
public record AperturaRequest(@NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal saldoInicial) {
}
