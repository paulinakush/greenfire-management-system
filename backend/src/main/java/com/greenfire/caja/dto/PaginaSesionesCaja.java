package com.greenfire.caja.dto;

import java.util.List;

/** Sesiones: resúmenes para el dueño; para el empleado, las de hoy con su vista a ciegas. */
public record PaginaSesionesCaja(List<?> sesiones, int pagina, int tamano, long total) {
}
