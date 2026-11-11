package com.greenfire.clientes.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Condiciones comerciales, solo para los dueños. Un límite null es sin límite; una lista null, sin lista propia
 * (en un local, se usa la predeterminada).
 */
public record ClienteCondicionesRequest(
        boolean cuentaCorrienteHabilitada,
        @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal limiteCredito,
        Long listaPreciosId,
        @Min(0) @Max(365) Integer plazoPagoDias) {
}
