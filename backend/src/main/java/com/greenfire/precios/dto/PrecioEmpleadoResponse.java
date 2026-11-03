package com.greenfire.precios.dto;

import java.math.BigDecimal;

/** Lo único que ve el empleado de un precio: el artículo y el precio de venta de su local. */
public record PrecioEmpleadoResponse(Long articuloId, String articuloNombre, BigDecimal precio) {
}
