package com.greenfire.ventas.dto;

import java.util.List;

public record PaginaVentas(List<? extends VentaVista> ventas, int pagina, int tamano, long total) {
}
