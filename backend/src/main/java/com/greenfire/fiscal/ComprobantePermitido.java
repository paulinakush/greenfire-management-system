package com.greenfire.fiscal;

import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

/** Un tipo y letra de comprobante que puede emitir un punto de venta (tabla punto_venta_comprobantes). */
@Embeddable
public record ComprobantePermitido(
        @Enumerated(EnumType.STRING) TipoComprobante tipo,
        @Enumerated(EnumType.STRING) LetraComprobante letra) {
}
