package com.greenfire.catalogo;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.greenfire.catalogo.dto.ArticuloPublicoResponse;
import com.greenfire.catalogo.dto.CatalogoPublicoResponse;
import com.greenfire.catalogo.dto.CategoriaPublicaResponse;
import com.greenfire.comun.CachePublico;

/** Catálogo del sitio público: sin login, solo lectura y con caché corto. */
@RestController
public class CatalogoPublicoController {

    private final CatalogoPublicoService service;

    public CatalogoPublicoController(CatalogoPublicoService service) {
        this.service = service;
    }

    @GetMapping("/api/publico/categorias")
    public ResponseEntity<List<CategoriaPublicaResponse>> categorias() {
        return ResponseEntity.ok().cacheControl(CachePublico.CORTO).body(service.categorias());
    }

    @GetMapping("/api/publico/catalogo")
    public ResponseEntity<CatalogoPublicoResponse> catalogo(@RequestParam(required = false) Long localId,
            @RequestParam(required = false) Long categoriaId, @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int pagina,
            @RequestParam(defaultValue = "" + CatalogoPublicoService.TAMANO_POR_DEFECTO) int tamano) {
        return ResponseEntity.ok().cacheControl(CachePublico.CORTO)
                .body(service.catalogo(localId, categoriaId, q, pagina, tamano));
    }

    @GetMapping("/api/publico/catalogo/{articuloId}")
    public ResponseEntity<ArticuloPublicoResponse> articulo(@PathVariable Long articuloId,
            @RequestParam(required = false) Long localId) {
        return ResponseEntity.ok().cacheControl(CachePublico.CORTO).body(service.articulo(articuloId, localId));
    }
}
