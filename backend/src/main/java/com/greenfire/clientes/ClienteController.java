package com.greenfire.clientes;

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

import com.greenfire.clientes.dto.ClienteCondicionesRequest;
import com.greenfire.clientes.dto.ClienteDatosRequest;
import com.greenfire.clientes.dto.ClienteRequest;
import com.greenfire.clientes.dto.ClienteResponse;
import com.greenfire.clientes.dto.ClienteVista;
import com.greenfire.clientes.dto.PaginaClientes;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/clientes")
public class ClienteController {

    private final ClienteService service;

    public ClienteController(ClienteService service) {
        this.service = service;
    }

    @GetMapping
    public PaginaClientes listar(@RequestParam(required = false) String q,
            @RequestParam(required = false) Boolean activo, @RequestParam(defaultValue = "0") int pagina,
            @RequestParam(defaultValue = "50") int tamano) {
        return service.listar(q, activo, pagina, tamano);
    }

    @GetMapping("/{id}")
    public ClienteVista obtener(@PathVariable Long id) {
        return service.obtener(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ClienteVista crear(@Valid @RequestBody ClienteRequest req) {
        return service.crear(req);
    }

    @PutMapping("/{id}")
    public ClienteVista editarDatos(@PathVariable Long id, @Valid @RequestBody ClienteDatosRequest req) {
        return service.editarDatos(id, req);
    }

    @PutMapping("/{id}/condiciones")
    public ClienteResponse editarCondiciones(@PathVariable Long id,
            @Valid @RequestBody ClienteCondicionesRequest req) {
        return service.editarCondiciones(id, req);
    }

    @PostMapping("/{id}/activar")
    public ClienteResponse activar(@PathVariable Long id) {
        return service.cambiarActivo(id, true);
    }

    @PostMapping("/{id}/desactivar")
    public ClienteResponse desactivar(@PathVariable Long id) {
        return service.cambiarActivo(id, false);
    }
}
