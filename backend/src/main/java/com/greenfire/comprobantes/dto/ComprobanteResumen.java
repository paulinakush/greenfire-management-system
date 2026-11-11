package com.greenfire.comprobantes.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import com.greenfire.comprobantes.Comprobante;
import com.greenfire.comprobantes.EstadoComprobante;
import com.greenfire.fiscal.LetraComprobante;
import com.greenfire.fiscal.TipoComprobante;

/** Un comprobante en el listado. No tiene datos de costo: los comprobantes no los guardan. */
public record ComprobanteResumen(
        Long id,
        TipoComprobante tipo,
        LetraComprobante letra,
        String numeroCompleto,
        Integer puntoVenta,
        Integer numero,
        OffsetDateTime fecha,
        EstadoComprobante estado,
        String emisorNombre,
        Long clienteId,
        String receptor,
        BigDecimal total) {

    public static ComprobanteResumen de(Comprobante c) {
        return new ComprobanteResumen(c.getId(), c.getTipo(), c.getLetra(), c.numeroCompleto(),
                c.getPuntoVenta().getNumero(), c.getNumero(), c.getFecha(), c.getEstado(), c.getEmisorNombre(),
                c.getCliente() == null ? null : c.getCliente().getId(), c.getReceptorRazonSocial(), c.getTotal());
    }
}
