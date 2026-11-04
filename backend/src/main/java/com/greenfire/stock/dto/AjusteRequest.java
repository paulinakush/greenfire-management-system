package com.greenfire.stock.dto;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Ajuste manual de stock con uno o varios renglones y un motivo obligatorio, común a todos. La cantidad lleva
 * signo: positiva si entra, negativa si sale. Si un renglón falla, no se aplica ninguno.
 */
public record AjusteRequest(
        @NotBlank @Size(max = 255) String motivo,
        @Size(max = 2000) String observaciones,
        @NotEmpty @Size(max = 200) List<@Valid @NotNull Renglon> renglones) {

    public record Renglon(
            @NotNull Long articuloId,
            @NotNull Long ubicacionId,
            @NotNull @Digits(integer = 11, fraction = 3) BigDecimal cantidad) {
    }
}
