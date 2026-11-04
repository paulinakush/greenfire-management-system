package com.greenfire.entregas;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.greenfire.entregas.dto.EntregaResponse;
import com.greenfire.entregas.dto.EntregaVista;
import com.greenfire.entregas.dto.EnviarEntregaRequest;
import com.greenfire.entregas.dto.RecepcionRequest;
import com.greenfire.entregas.dto.ResolverDiferenciaRequest;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/entregas")
public class EntregaController {

    private final EntregaService service;

    public EntregaController(EntregaService service) {
        this.service = service;
    }

    @GetMapping
    public List<EntregaVista> listar(@RequestParam(required = false) EstadoEntrega estado) {
        return service.listar(estado);
    }

    @GetMapping("/{id}")
    public EntregaVista obtener(@PathVariable Long id) {
        return service.obtener(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public EntregaResponse enviar(@Valid @RequestBody EnviarEntregaRequest req) {
        return service.enviar(req);
    }

    @PostMapping("/{id}/anular")
    public EntregaResponse anular(@PathVariable Long id) {
        return service.anular(id);
    }

    @PostMapping("/{id}/recepcion")
    public EntregaVista recibir(@PathVariable Long id, @Valid @RequestBody RecepcionRequest req) {
        return service.recibir(id, req);
    }

    @PostMapping("/{id}/resolver-diferencia")
    public EntregaResponse resolverDiferencia(@PathVariable Long id, @Valid @RequestBody ResolverDiferenciaRequest req) {
        return service.resolverDiferencia(id, req);
    }
}
