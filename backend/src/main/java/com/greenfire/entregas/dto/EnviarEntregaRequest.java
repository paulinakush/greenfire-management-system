package com.greenfire.entregas.dto;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** Envío de la fábrica a un local. El precio de cada renglón sale de la lista indicada, no del pedido. */
public record EnviarEntregaRequest(
        @NotNull Long localId,
        @NotNull Long ubicacionOrigenId,
        @NotNull Long listaPreciosId,
        @Size(max = 2000) String observaciones,
        @NotEmpty @Size(max = 200) List<@Valid @NotNull Renglon> renglones) {

    /** {@code loteId} es opcional y queda null hasta la etapa 4. */
    public record Renglon(
            @NotNull Long articuloId,
            @NotNull @Positive @Digits(integer = 11, fraction = 3) BigDecimal cantidad,
            Long loteId) {
    }
}
