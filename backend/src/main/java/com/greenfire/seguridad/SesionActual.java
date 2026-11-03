package com.greenfire.seguridad;

import java.util.Optional;

import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

/** Lee la {@link Sesion} guardada en la sesión HTTP del request en curso. */
@Component
public class SesionActual {

    public Optional<Sesion> obtener() {
        RequestAttributes request = RequestContextHolder.getRequestAttributes();
        if (request == null) {
            return Optional.empty();
        }
        return Optional.ofNullable((Sesion) request.getAttribute(Sesion.ATRIBUTO, RequestAttributes.SCOPE_SESSION));
    }
}
