package com.greenfire.fiscal.dto;

import java.util.Comparator;
import java.util.List;

import com.greenfire.fiscal.ComprobantePermitido;
import com.greenfire.fiscal.PuntoVenta;

public record PuntoVentaResponse(
        Long id,
        Long entidadFiscalId,
        Long ambitoId,
        Integer numero,
        String descripcion,
        boolean activo,
        List<ComprobantePermitido> comprobantes) {

    public static PuntoVentaResponse de(PuntoVenta p) {
        List<ComprobantePermitido> comprobantes = p.getComprobantes().stream()
                .sorted(Comparator.comparing(ComprobantePermitido::tipo).thenComparing(ComprobantePermitido::letra))
                .toList();
        return new PuntoVentaResponse(p.getId(), p.getEntidadFiscal().getId(), p.getAmbito().getId(), p.getNumero(),
                p.getDescripcion(), p.isActivo(), comprobantes);
    }
}
