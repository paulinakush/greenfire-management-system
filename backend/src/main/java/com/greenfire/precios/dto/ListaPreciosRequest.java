package com.greenfire.precios.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Alta o edición de una lista. {@code preciosConIva} debe ser true si la entidad del ámbito no discrimina IVA. */
public record ListaPreciosRequest(
        @NotBlank @Size(max = 120) String nombre,
        @NotNull Boolean preciosConIva,
        boolean esPredeterminada) {
}
