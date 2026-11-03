package com.greenfire.auditoria;

/** No se pudo guardar la auditoría: la operación que la generó se revierte entera. */
public class AuditoriaException extends RuntimeException {

    public AuditoriaException(Throwable causa) {
        super("No se pudo registrar la auditoría, así que la operación no se realizó.", causa);
    }
}
