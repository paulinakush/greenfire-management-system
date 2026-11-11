package com.greenfire.ventas.dto;

import java.math.BigDecimal;

import com.greenfire.comprobantes.Comprobante;
import com.greenfire.comprobantes.EstadoComprobante;
import com.greenfire.fiscal.LetraComprobante;
import com.greenfire.fiscal.TipoComprobante;

/** El comprobante de una venta o la nota de crédito de una devolución, para mostrar e imprimir. */
public record ComprobanteDeVenta(Long id, TipoComprobante tipo, LetraComprobante letra, String numero,
        EstadoComprobante estado, BigDecimal total) {

    public static ComprobanteDeVenta de(Comprobante c) {
        return new ComprobanteDeVenta(c.getId(), c.getTipo(), c.getLetra(), c.numeroCompleto(), c.getEstado(),
                c.getTotal());
    }
}
