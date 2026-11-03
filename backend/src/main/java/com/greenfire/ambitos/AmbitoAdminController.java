package com.greenfire.ambitos;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.greenfire.ambitos.dto.AmbitoEdicionAdminRequest;
import com.greenfire.ambitos.dto.AmbitoRequest;
import com.greenfire.ambitos.dto.AmbitoResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/admin/ambitos")
public class AmbitoAdminController {

    private final AmbitoService service;

    public AmbitoAdminController(AmbitoService service) {
        this.service = service;
    }

    @GetMapping
    public List<AmbitoResponse> listar() {
        return service.listar();
    }

    @GetMapping("/{id}")
    public AmbitoResponse obtener(@PathVariable Long id) {
        return service.obtener(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AmbitoResponse crear(@Valid @RequestBody AmbitoRequest req) {
        return service.crear(req);
    }

    @PutMapping("/{id}")
    public AmbitoResponse editar(@PathVariable Long id, @Valid @RequestBody AmbitoEdicionAdminRequest req) {
        return service.editar(id, req);
    }

    @PostMapping("/{id}/activar")
    public AmbitoResponse activar(@PathVariable Long id) {
        return service.cambiarActivo(id, true);
    }

    @PostMapping("/{id}/desactivar")
    public AmbitoResponse desactivar(@PathVariable Long id) {
        return service.cambiarActivo(id, false);
    }

    /** Marca el local central, el que el catálogo público usa por defecto. Desmarca al anterior. */
    @PostMapping("/{id}/central")
    public AmbitoResponse marcarCentral(@PathVariable Long id) {
        return service.marcarCentral(id);
    }
}
