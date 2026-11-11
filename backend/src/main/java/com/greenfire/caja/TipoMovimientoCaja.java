package com.greenfire.caja;

public enum TipoMovimientoCaja {
    ingreso,
    egreso;

    public TipoMovimientoCaja contrario() {
        return this == ingreso ? egreso : ingreso;
    }
}
