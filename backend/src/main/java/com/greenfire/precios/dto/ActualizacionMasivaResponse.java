package com.greenfire.precios.dto;

/** Cuántos precios cambiaron. Los que quedaron iguales después del redondeo no cuentan. */
public record ActualizacionMasivaResponse(int actualizados) {
}
