package com.greenfire.importacion;

/** El archivo supera el tamaño máximo. La API responde 413. */
public class ArchivoDemasiadoGrandeException extends RuntimeException {

    public ArchivoDemasiadoGrandeException(String mensaje) {
        super(mensaje);
    }
}
