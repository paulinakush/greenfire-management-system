package com.greenfire.fiscal.dto;

import java.time.LocalDate;

import com.greenfire.fiscal.CondicionIva;
import com.greenfire.fiscal.EntidadFiscal;

public record EntidadFiscalResponse(
        Long id,
        String razonSocial,
        String cuit,
        CondicionIva condicionIva,
        String domicilioFiscal,
        String arcaCertificadoRef,
        LocalDate arcaCertificadoVence,
        boolean activo) {

    public static EntidadFiscalResponse de(EntidadFiscal e) {
        return new EntidadFiscalResponse(e.getId(), e.getRazonSocial(), e.getCuit(), e.getCondicionIva(),
                e.getDomicilioFiscal(), e.getArcaCertificadoRef(), e.getArcaCertificadoVence(), e.isActivo());
    }
}
