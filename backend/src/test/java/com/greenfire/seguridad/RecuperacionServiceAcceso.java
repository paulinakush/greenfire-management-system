package com.greenfire.seguridad;

/** Expone a los tests de otros paquetes el hash con que se guardan los tokens de recuperación. */
public final class RecuperacionServiceAcceso {

    private RecuperacionServiceAcceso() {
    }

    public static String sha256(String token) {
        return RecuperacionService.sha256(token);
    }
}
