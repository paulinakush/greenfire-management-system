package com.greenfire.stock;

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

import com.greenfire.stock.dto.AbrirConteoRequest;
import com.greenfire.stock.dto.CargaConteoRequest;
import com.greenfire.stock.dto.ConteoResponse;
import com.greenfire.stock.dto.ConteoResumenResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/conteos")
public class ConteoController {

    private final ConteoService service;

    public ConteoController(ConteoService service) {
        this.service = service;
    }

    @GetMapping
    public List<ConteoResumenResponse> listar(@RequestParam(required = false) Long ubicacionId,
            @RequestParam(required = false) EstadoConteo estado) {
        return service.listar(ubicacionId, estado);
    }

    @GetMapping("/{id}")
    public ConteoResponse obtener(@PathVariable Long id) {
        return service.obtener(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ConteoResponse abrir(@Valid @RequestBody AbrirConteoRequest req) {
        return service.abrir(req);
    }

    @PutMapping("/{id}/items")
    public ConteoResponse cargar(@PathVariable Long id, @Valid @RequestBody CargaConteoRequest req) {
        return service.cargar(id, req);
    }

    @PostMapping("/{id}/cerrar")
    public ConteoResponse cerrar(@PathVariable Long id) {
        return service.cerrar(id);
    }

    @PostMapping("/{id}/anular")
    public ConteoResponse anular(@PathVariable Long id) {
        return service.anular(id);
    }
}
