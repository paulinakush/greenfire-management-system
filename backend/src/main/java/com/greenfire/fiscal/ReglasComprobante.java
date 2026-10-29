package com.greenfire.fiscal;

import java.util.Set;

/**
 * Qué letras de factura y nota de crédito puede emitir una entidad según su condición frente al IVA.
 * Se usa solo para validar la configuración de cada punto de venta, no al emitir comprobantes.
 *
 * PENDIENTE: confirmar estas reglas con el contador antes de la etapa de facturación con ARCA.
 */
public final class ReglasComprobante {

    private ReglasComprobante() {
    }

    public static Set<LetraComprobante> letrasFiscales(CondicionIva emisor) {
        return switch (emisor) {
            case responsable_inscripto -> Set.of(LetraComprobante.A, LetraComprobante.B);
            case monotributo, exento -> Set.of(LetraComprobante.C);
            case consumidor_final -> Set.of();
        };
    }

    public static boolean permitido(CondicionIva emisor, ComprobantePermitido comprobante) {
        return !comprobante.tipo().esFiscal() || letrasFiscales(emisor).contains(comprobante.letra());
    }
}
