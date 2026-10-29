package com.greenfire.fiscal;

public enum TipoComprobante {
    factura,
    nota_credito,
    remito,
    recibo,
    presupuesto;

    /** Factura y nota de crédito: su letra depende de la condición frente al IVA del emisor. */
    public boolean esFiscal() {
        return this == factura || this == nota_credito;
    }
}
