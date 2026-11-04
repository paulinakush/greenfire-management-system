package com.greenfire.entregas.dto;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Recepción en el local: la ubicación de destino y la cantidad recibida de cada renglón de la entrega. */
public record RecepcionRequest(
        @NotNull Long ubicacionId,
        @Size(max = 2000) String observaciones,
        @NotEmpty @Size(max = 200) List<@Valid @NotNull Renglon> renglones) {

    public record Renglon(
            @NotNull Long renglonId,
            @NotNull @DecimalMin("0") @Digits(integer = 11, fraction = 3) BigDecimal cantidadRecibida) {
    }
}
