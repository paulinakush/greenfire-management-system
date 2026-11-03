package com.greenfire.ambitos.dto;

import com.greenfire.ambitos.Ambito;
import com.greenfire.ambitos.TipoAmbito;

public record AmbitoResponse(
        Long id,
        TipoAmbito tipo,
        String nombre,
        Long entidadFiscalId,
        String entidadFiscalRazonSocial,
        String direccion,
        String telefono,
        String email,
        String horarios,
        boolean visibleWeb,
        boolean esCentral,
        boolean activo) {

    public static AmbitoResponse de(Ambito a) {
        return new AmbitoResponse(a.getId(), a.getTipo(), a.getNombre(), a.getEntidadFiscal().getId(),
                a.getEntidadFiscal().getRazonSocial(), a.getDireccion(), a.getTelefono(), a.getEmail(),
                a.getHorarios(), a.isVisibleWeb(), a.isEsCentral(), a.isActivo());
    }
}
