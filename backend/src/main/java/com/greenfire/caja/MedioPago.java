package com.greenfire.caja;

/**
 * Medio de pago, como en el modelo. La cuenta corriente no pasa por la caja: un movimiento de caja es en efectivo,
 * transferencia o billetera virtual.
 */
public enum MedioPago {
    efectivo,
    transferencia,
    billetera_virtual,
    cuenta_corriente;

    public boolean pasaPorCaja() {
        return this != cuenta_corriente;
    }
}
