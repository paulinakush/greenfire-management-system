package com.greenfire.ambitos.dto;

import com.greenfire.ambitos.Ambito;

/** Datos de un local que se muestran en la sección "Sucursales" del sitio público. Nada fiscal ni interno. */
public record LocalPublicoResponse(String nombre, String direccion, String horarios, String telefono, String email) {

    public static LocalPublicoResponse de(Ambito a) {
        return new LocalPublicoResponse(a.getNombre(), a.getDireccion(), a.getHorarios(), a.getTelefono(),
                a.getEmail());
    }
}
