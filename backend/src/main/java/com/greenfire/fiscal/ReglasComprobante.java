package com.greenfire.fiscal;

import java.util.EnumSet;
import java.util.Set;

/**
 * Qué letras puede emitir una entidad de cada tipo de comprobante, según su condición frente al IVA. Se usa para
 * validar la configuración de cada punto de venta.
 * <ul>
 * <li>Factura: solo las letras fiscales de la condición del emisor (nunca X).</li>
 * <li>Nota de crédito y de débito: las letras fiscales, o X (no fiscal) para cualquier emisor.</li>
 * <li>Comprobante de venta: solo X.</li>
 * <li>Remito, recibo y presupuesto: cualquier letra, como hasta ahora.</li>
 * </ul>
 * PENDIENTE: confirmar las letras fiscales con el contador antes de la etapa de facturación con ARCA.
 */
public final class ReglasComprobante {

    /** La leyenda que llevan todos los comprobantes X. */
    public static final String LEYENDA_NO_FISCAL = "Documento no válido como factura";

    private ReglasComprobante() {
    }

    public static Set<LetraComprobante> letrasFiscales(CondicionIva emisor) {
        return switch (emisor) {
            case responsable_inscripto -> EnumSet.of(LetraComprobante.A, LetraComprobante.B);
            case monotributo, exento -> EnumSet.of(LetraComprobante.C);
            case consumidor_final -> EnumSet.noneOf(LetraComprobante.class);
        };
    }

    public static Set<LetraComprobante> letras(CondicionIva emisor, TipoComprobante tipo) {
        return switch (tipo) {
            case factura -> letrasFiscales(emisor);
            case nota_credito, nota_debito -> {
                Set<LetraComprobante> letras = letrasFiscales(emisor);
                letras.add(LetraComprobante.X);
                yield letras;
            }
            case comprobante_venta -> EnumSet.of(LetraComprobante.X);
            case remito, recibo, presupuesto -> EnumSet.allOf(LetraComprobante.class);
        };
    }

    public static boolean permitido(CondicionIva emisor, ComprobantePermitido comprobante) {
        return letras(emisor, comprobante.tipo()).contains(comprobante.letra());
    }
}
