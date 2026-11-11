package com.greenfire.ventas.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.greenfire.caja.MedioPago;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

/**
 * Una venta: cliente (opcional en un local, obligatorio en la fábrica), medio de pago, porcentaje de descuento
 * (0 si falta), fecha de cobro (solo en la fábrica; hoy si falta) y renglones.
 */
public record VentaRequest(
        Long clienteId,
        @NotNull MedioPago medioPago,
        BigDecimal porcentajeDescuento,
        LocalDate fechaCobro,
        @NotNull List<@Valid @NotNull Renglon> renglones) {

    /** Un artículo y su cantidad; si no se indica la ubicación, sale de "Principal". */
    public record Renglon(@NotNull Long articuloId, @NotNull BigDecimal cantidad, Long ubicacionId) {
    }
}
