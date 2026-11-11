package com.greenfire.caja;

/**
 * De dónde sale un movimiento de caja. Los manuales los carga la persona; los demás, las ventas (módulo 5) y las
 * cobranzas (módulo 7), con {@link CajaService#registrarCobro}.
 */
public enum OrigenMovimientoCaja {
    manual,
    venta,
    cobranza,
    /** Devolución o anulación de una venta: un egreso. */
    devolucion;

    /** El tipo que corresponde a un movimiento que no es manual. */
    public TipoMovimientoCaja tipo() {
        return switch (this) {
            case venta, cobranza -> TipoMovimientoCaja.ingreso;
            case devolucion -> TipoMovimientoCaja.egreso;
            case manual -> throw new IllegalStateException("El tipo de un movimiento manual lo elige la persona.");
        };
    }
}
