package com.greenfire.fiscal;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

/**
 * Un tipo y letra de comprobante que puede emitir un punto de venta (tabla punto_venta_comprobantes). Las columnas
 * van como no nulas para que Hibernate, al editar el conjunto, borre e inserte solo las filas que cambian, sin
 * recrear las demás (que guardan el último número emitido).
 */
@Embeddable
public record ComprobantePermitido(
        @Column(nullable = false) @Enumerated(EnumType.STRING) TipoComprobante tipo,
        @Column(nullable = false) @Enumerated(EnumType.STRING) LetraComprobante letra) {
}
