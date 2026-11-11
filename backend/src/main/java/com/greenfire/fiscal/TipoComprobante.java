package com.greenfire.fiscal;

/** Las letras que admite cada tipo según la condición del emisor están en {@link ReglasComprobante}. */
public enum TipoComprobante {
    factura,
    nota_credito,
    nota_debito,
    remito,
    recibo,
    presupuesto,
    /** Comprobante simple de una venta, sin factura. Solo letra X. */
    comprobante_venta;

    /** Nombre para mostrar en el comprobante impreso. */
    public String nombre() {
        return switch (this) {
            case factura -> "Factura";
            case nota_credito -> "Nota de crédito";
            case nota_debito -> "Nota de débito";
            case remito -> "Remito";
            case recibo -> "Recibo";
            case presupuesto -> "Presupuesto";
            case comprobante_venta -> "Comprobante de venta";
        };
    }
}
