package com.greenfire.precios.dto;

import java.util.List;

public record PaginaHistorial(List<HistorialPrecioResponse> registros, int pagina, int tamano, long total) {
}
