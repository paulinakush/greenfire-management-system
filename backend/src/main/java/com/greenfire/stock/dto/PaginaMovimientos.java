package com.greenfire.stock.dto;

import java.util.List;

public record PaginaMovimientos(List<MovimientoResponse> movimientos, int pagina, int tamano, long total) {
}
