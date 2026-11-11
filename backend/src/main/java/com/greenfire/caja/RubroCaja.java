package com.greenfire.caja;

import java.util.EnumSet;
import java.util.Set;

/** Rubro de un movimiento manual (CHECK en V13). */
public enum RubroCaja {
    gasto,
    retiro,
    aporte,
    pago_fabrica,
    otro;

    /** Los que registra el empleado: no hace retiros ni pagos a la fábrica (regla 2 de CLAUDE.md). */
    public static final Set<RubroCaja> DEL_EMPLEADO = EnumSet.of(gasto, aporte, otro);

    /** El tipo que corresponde al rubro; null para "otro", que puede ser ingreso o egreso. */
    public TipoMovimientoCaja tipoNatural() {
        return switch (this) {
            case gasto, retiro, pago_fabrica -> TipoMovimientoCaja.egreso;
            case aporte -> TipoMovimientoCaja.ingreso;
            case otro -> null;
        };
    }
}
