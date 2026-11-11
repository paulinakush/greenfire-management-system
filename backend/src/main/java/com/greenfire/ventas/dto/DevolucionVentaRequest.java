package com.greenfire.ventas.dto;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Devolución parcial: los renglones de la venta que vuelven, con su cantidad, y el motivo. */
public record DevolucionVentaRequest(
        @NotBlank @Size(max = 500) String motivo,
        @NotNull List<@Valid @NotNull Renglon> renglones) {

    public record Renglon(@NotNull Long detalleVentaId, @NotNull BigDecimal cantidad) {
    }
}
