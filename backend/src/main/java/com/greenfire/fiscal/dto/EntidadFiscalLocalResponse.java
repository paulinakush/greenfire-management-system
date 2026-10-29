package com.greenfire.fiscal.dto;

import java.util.List;

import com.greenfire.fiscal.CondicionIva;
import com.greenfire.fiscal.EntidadFiscal;
import com.greenfire.fiscal.PuntoVenta;

/** Lo que el dueño de local ve de la entidad fiscal de su local. Sin datos del certificado de ARCA. */
public record EntidadFiscalLocalResponse(
        String razonSocial,
        String cuit,
        CondicionIva condicionIva,
        List<PuntoVentaLocal> puntosVenta) {

    public record PuntoVentaLocal(Integer numero, String descripcion, boolean activo, List<String> comprobantes) {
    }

    public static EntidadFiscalLocalResponse de(EntidadFiscal e, List<PuntoVenta> puntosVenta) {
        List<PuntoVentaLocal> puntos = puntosVenta.stream()
                .map(p -> new PuntoVentaLocal(p.getNumero(), p.getDescripcion(), p.isActivo(),
                        p.getComprobantes().stream().map(c -> c.tipo() + " " + c.letra()).sorted().toList()))
                .toList();
        return new EntidadFiscalLocalResponse(e.getRazonSocial(), e.getCuit(), e.getCondicionIva(), puntos);
    }
}
