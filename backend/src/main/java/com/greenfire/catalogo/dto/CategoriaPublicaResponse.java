package com.greenfire.catalogo.dto;

import com.greenfire.catalogo.Categoria;

/** Categoría del catálogo público. */
public record CategoriaPublicaResponse(Long id, String nombre, String descripcion) {

    public static CategoriaPublicaResponse de(Categoria c) {
        return new CategoriaPublicaResponse(c.getId(), c.getNombre(), c.getDescripcion());
    }
}
