package com.greenfire.stock.dto;

import com.greenfire.stock.UbicacionStock;

public record UbicacionResponse(Long id, String nombre, boolean activo) {

    public static UbicacionResponse de(UbicacionStock u) {
        return new UbicacionResponse(u.getId(), u.getNombre(), u.isActivo());
    }
}
