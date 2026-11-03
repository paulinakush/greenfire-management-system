package com.greenfire.fiscal.dto;

import java.util.Set;

import com.greenfire.fiscal.ComprobantePermitido;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Edición de un punto de venta. El ámbito no se cambia. */
public record PuntoVentaEdicionRequest(
        @NotNull @Min(1) @Max(99999) Integer numero,
        @Size(max = 120) String descripcion,
        @NotNull Set<@NotNull ComprobantePermitido> comprobantes) {
}
