package com.greenfire.comun;

public class AccesoDenegadoException extends RuntimeException {

    public AccesoDenegadoException() {
        super("No tenés permiso para realizar esta acción.");
    }

    /** Con un mensaje que explica el límite del permiso, por ejemplo el descuento máximo del empleado. */
    public AccesoDenegadoException(String mensaje) {
        super(mensaje);
    }
}
