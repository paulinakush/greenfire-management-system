package com.greenfire.ambitos.dto;

import java.math.BigDecimal;

import com.greenfire.ambitos.Ambito;

public record CondicionesVentaResponse(Long ambitoId, BigDecimal descuentoMaximoEmpleado) {

    public static CondicionesVentaResponse de(Ambito a) {
        return new CondicionesVentaResponse(a.getId(), a.getDescuentoMaximoEmpleado());
    }
}
