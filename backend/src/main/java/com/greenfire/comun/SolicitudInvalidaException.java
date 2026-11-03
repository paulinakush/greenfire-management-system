package com.greenfire.comun;

/** Un parámetro de la solicitud fuera de lo permitido. La API responde 400. */
public class SolicitudInvalidaException extends RuntimeException {

    public SolicitudInvalidaException(String mensaje) {
        super(mensaje);
    }
}
