package com.greenfire.comun;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Acumula los campos que cambiaron en una edición, con su valor anterior y el nuevo. */
public class Cambios {

    private final Map<String, Map<String, Object>> campos = new LinkedHashMap<>();

    public <T> T registrar(String campo, T antes, T despues) {
        if (!Objects.equals(antes, despues)) {
            Map<String, Object> valores = new LinkedHashMap<>();
            valores.put("antes", antes);
            valores.put("despues", despues);
            campos.put(campo, valores);
        }
        return despues;
    }

    public boolean vacio() {
        return campos.isEmpty();
    }

    public Map<String, Map<String, Object>> comoMapa() {
        return campos;
    }
}
