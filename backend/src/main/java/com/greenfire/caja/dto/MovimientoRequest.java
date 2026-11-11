package com.greenfire.caja.dto;

import java.math.BigDecimal;

import com.greenfire.caja.MedioPago;
import com.greenfire.caja.RubroCaja;
import com.greenfire.caja.TipoMovimientoCaja;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Movimiento manual de la caja abierta. El tipo sale del rubro (gasto, retiro y pago a la fábrica son egresos; el
 * aporte, ingreso); solo "otro" necesita el tipo. Para corregir un error se indica {@code corrigeMovimientoId}: el
 * tipo, el rubro y el medio salen del movimiento original (el tipo, al revés).
 */
public record MovimientoRequest(
        TipoMovimientoCaja tipo,
        RubroCaja rubro,
        MedioPago medioPago,
        @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 12, fraction = 2) BigDecimal monto,
        @NotBlank @Size(max = 500) String observacion,
        Long corrigeMovimientoId) {
}
