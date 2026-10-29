package com.greenfire.comun;

public class AccesoDenegadoException extends RuntimeException {

    public AccesoDenegadoException() {
        super("No tenés permiso para realizar esta acción.");
    }
}
