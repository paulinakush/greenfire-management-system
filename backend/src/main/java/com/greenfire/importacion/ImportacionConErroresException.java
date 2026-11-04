package com.greenfire.importacion;

import com.greenfire.importacion.dto.ResultadoImportacion;

/** Una confirmación con filas con error: no se aplica nada y la API responde 409 con el detalle de cada fila. */
public class ImportacionConErroresException extends RuntimeException {

    private final transient ResultadoImportacion resultado;

    public ImportacionConErroresException(ResultadoImportacion resultado) {
        super("El archivo tiene " + resultado.errores() + " filas con error: no se aplicó ninguna.");
        this.resultado = resultado;
    }

    public ResultadoImportacion getResultado() {
        return resultado;
    }
}
