package com.greenfire.ventas;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.greenfire.caja.MedioPago;
import com.greenfire.ventas.dto.AnulacionVentaRequest;
import com.greenfire.ventas.dto.DevolucionVentaRequest;
import com.greenfire.ventas.dto.HistorialClienteResponse;
import com.greenfire.ventas.dto.PaginaVentas;
import com.greenfire.ventas.dto.VentaRequest;
import com.greenfire.ventas.dto.VentaResponse;
import com.greenfire.ventas.dto.VentaVista;

import jakarta.validation.Valid;

@RestController
public class VentaController {

    private final VentaService service;

    public VentaController(VentaService service) {
        this.service = service;
    }

    @PostMapping("/api/ventas")
    @ResponseStatus(HttpStatus.CREATED)
    public VentaVista registrar(@Valid @RequestBody VentaRequest req) {
        return service.registrar(req);
    }

    @GetMapping("/api/ventas")
    public PaginaVentas listar(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
            @RequestParam(required = false) Long clienteId, @RequestParam(required = false) MedioPago medio,
            @RequestParam(required = false) EstadoVenta estado, @RequestParam(defaultValue = "0") int pagina,
            @RequestParam(defaultValue = "50") int tamano) {
        return service.listar(desde, hasta, clienteId, medio, estado, pagina, tamano);
    }

    @GetMapping("/api/ventas/{id}")
    public VentaVista obtener(@PathVariable Long id) {
        return service.obtener(id);
    }

    @PostMapping("/api/ventas/{id}/anular")
    public VentaResponse anular(@PathVariable Long id, @Valid @RequestBody AnulacionVentaRequest req) {
        return service.anular(id, req);
    }

    @PostMapping("/api/ventas/{id}/devoluciones")
    @ResponseStatus(HttpStatus.CREATED)
    public VentaResponse devolver(@PathVariable Long id, @Valid @RequestBody DevolucionVentaRequest req) {
        return service.devolver(id, req);
    }

    /** Las ventas de un cliente, con los mismos permisos que el cliente. */
    @GetMapping("/api/clientes/{id}/historial")
    public HistorialClienteResponse historialCliente(@PathVariable Long id, @RequestParam(defaultValue = "0") int pagina,
            @RequestParam(defaultValue = "50") int tamano) {
        return service.historialCliente(id, pagina, tamano);
    }
}
