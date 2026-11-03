package com.greenfire.comun;

public class NoAutenticadoException extends RuntimeException {

    public NoAutenticadoException() {
        super("No hay una sesión iniciada.");
    }

    public NoAutenticadoException(String mensaje) {
        super(mensaje);
    }
}
