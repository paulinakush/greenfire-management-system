package com.greenfire.comprobantes.dto;

import java.util.List;

public record PaginaComprobantes(List<ComprobanteResumen> comprobantes, int pagina, int tamano, long total) {
}
