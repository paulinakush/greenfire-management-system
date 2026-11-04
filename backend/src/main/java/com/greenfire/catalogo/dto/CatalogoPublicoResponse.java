package com.greenfire.catalogo.dto;

import java.util.List;

/** Una página del catálogo público de un local. */
public record CatalogoPublicoResponse(
        Long localId,
        String localNombre,
        List<ArticuloPublicoResponse> articulos,
        int pagina,
        int tamano,
        long total) {
}
