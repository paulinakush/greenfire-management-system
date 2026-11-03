package com.greenfire.fiscal;

public enum CondicionIva {
    responsable_inscripto,
    monotributo,
    exento,
    consumidor_final;

    /**
     * Solo el responsable inscripto discrimina IVA. Para las demás condiciones, el precio de una lista es el
     * precio final y el margen se calcula sobre él, sin sacar IVA.
     */
    public boolean discriminaIva() {
        return this == responsable_inscripto;
    }
}
