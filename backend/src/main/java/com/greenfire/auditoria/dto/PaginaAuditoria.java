package com.greenfire.auditoria.dto;

import java.util.List;

public record PaginaAuditoria(List<AuditoriaResponse> registros, int pagina, int tamano, long total) {
}
