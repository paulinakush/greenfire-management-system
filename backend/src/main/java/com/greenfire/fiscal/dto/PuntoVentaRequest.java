package com.greenfire.fiscal.dto;

import java.util.Set;

import com.greenfire.fiscal.ComprobantePermitido;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Alta de un punto de venta. La entidad fiscal es la del ámbito. */
public record PuntoVentaRequest(
        @NotNull Long ambitoId,
        @NotNull @Min(1) @Max(99999) Integer numero,
        @Size(max = 120) String descripcion,
        @NotNull Set<@NotNull ComprobantePermitido> comprobantes) {
}
