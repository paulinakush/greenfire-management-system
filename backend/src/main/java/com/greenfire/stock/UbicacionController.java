package com.greenfire.stock;

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

import com.greenfire.stock.dto.UbicacionRequest;
import com.greenfire.stock.dto.UbicacionResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/ubicaciones")
public class UbicacionController {

    private final UbicacionService service;

    public UbicacionController(UbicacionService service) {
        this.service = service;
    }

    @GetMapping
    public List<UbicacionResponse> listar() {
        return service.listar();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public UbicacionResponse crear(@Valid @RequestBody UbicacionRequest req) {
        return service.crear(req);
    }

    @PutMapping("/{id}")
    public UbicacionResponse editar(@PathVariable Long id, @Valid @RequestBody UbicacionRequest req) {
        return service.editar(id, req);
    }

    @PostMapping("/{id}/activar")
    public UbicacionResponse activar(@PathVariable Long id) {
        return service.cambiarActivo(id, true);
    }

    @PostMapping("/{id}/desactivar")
    public UbicacionResponse desactivar(@PathVariable Long id) {
        return service.cambiarActivo(id, false);
    }
}
