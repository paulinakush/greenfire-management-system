package com.greenfire.importacion.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * El resultado de una fila: qué se haría (o se hizo) con ella, o el motivo del error. {@code campos} son los campos
 * que cambian en un artículo; {@code precioAnterior} y {@code precioNuevo}, el precio en una importación de precios.
 */
public record FilaImportacion(
        int fila,
        AccionFila accion,
        String nombre,
        String motivo,
        List<String> campos,
        BigDecimal precioAnterior,
        BigDecimal precioNuevo) {

    public enum AccionFila {
        crear,
        actualizar,
        sin_cambios,
        error
    }
}
