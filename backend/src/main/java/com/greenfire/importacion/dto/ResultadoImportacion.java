package com.greenfire.importacion.dto;

import java.util.List;

/**
 * La vista previa o el resultado de aplicar un archivo. {@code aplicado} es true solo si se guardó: con alguna fila
 * con error no se aplica ninguna.
 */
public record ResultadoImportacion(
        String archivo,
        boolean aplicado,
        int filas,
        int crear,
        int actualizar,
        int sinCambios,
        int errores,
        List<FilaImportacion> detalle) {

    public static ResultadoImportacion de(String archivo, boolean aplicado, List<FilaImportacion> detalle) {
        return new ResultadoImportacion(archivo, aplicado, detalle.size(),
                contar(detalle, FilaImportacion.AccionFila.crear), contar(detalle, FilaImportacion.AccionFila.actualizar),
                contar(detalle, FilaImportacion.AccionFila.sin_cambios), contar(detalle, FilaImportacion.AccionFila.error),
                detalle);
    }

    public boolean tieneErrores() {
        return errores > 0;
    }

    private static int contar(List<FilaImportacion> detalle, FilaImportacion.AccionFila accion) {
        return (int) detalle.stream().filter(f -> f.accion() == accion).count();
    }
}
