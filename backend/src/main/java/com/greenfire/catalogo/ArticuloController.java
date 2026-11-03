package com.greenfire.catalogo;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.greenfire.catalogo.dto.ArticuloEdicionRequest;
import com.greenfire.catalogo.dto.ArticuloRequest;
import com.greenfire.catalogo.dto.ArticuloResponse;
import com.greenfire.catalogo.dto.ArticuloVista;
import com.greenfire.catalogo.dto.CostoRequest;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/articulos")
public class ArticuloController {

    private final ArticuloService service;

    public ArticuloController(ArticuloService service) {
        this.service = service;
    }

    @GetMapping
    public List<ArticuloVista> listar(@RequestParam(required = false) TipoArticulo tipo,
            @RequestParam(required = false) Long categoriaId, @RequestParam(required = false) String q,
            @RequestParam(required = false) Boolean activo) {
        return service.listar(tipo, categoriaId, q, activo);
    }

    @GetMapping("/{id}")
    public ArticuloVista obtener(@PathVariable Long id) {
        return service.obtener(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ArticuloResponse crear(@Valid @RequestBody ArticuloRequest req) {
        return service.crear(req);
    }

    @PutMapping("/{id}")
    public ArticuloResponse editar(@PathVariable Long id, @Valid @RequestBody ArticuloEdicionRequest req) {
        return service.editar(id, req);
    }

    @PostMapping("/{id}/activar")
    public ArticuloResponse activar(@PathVariable Long id) {
        return service.cambiarActivo(id, true);
    }

    @PostMapping("/{id}/desactivar")
    public ArticuloResponse desactivar(@PathVariable Long id) {
        return service.cambiarActivo(id, false);
    }

    @PutMapping("/{id}/costo")
    public ArticuloResponse editarCosto(@PathVariable Long id, @Valid @RequestBody CostoRequest req) {
        return service.editarCosto(id, req);
    }
}
