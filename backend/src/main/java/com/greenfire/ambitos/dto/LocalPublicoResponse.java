package com.greenfire.ambitos.dto;

import com.greenfire.ambitos.Ambito;

/**
 * Datos de un local que se muestran en la sección "Sucursales" del sitio público. Nada fiscal ni interno. El id
 * sirve para elegir el local en el catálogo público; {@code esCentral} marca el que se usa por defecto.
 */
public record LocalPublicoResponse(Long id, String nombre, String direccion, String horarios, String telefono,
        String email, boolean esCentral) {

    public static LocalPublicoResponse de(Ambito a) {
        return new LocalPublicoResponse(a.getId(), a.getNombre(), a.getDireccion(), a.getHorarios(),
                a.getTelefono(), a.getEmail(), a.isEsCentral());
    }
}
