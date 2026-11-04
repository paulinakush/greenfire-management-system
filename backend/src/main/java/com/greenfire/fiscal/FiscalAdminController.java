package com.greenfire.fiscal;

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

import com.greenfire.fiscal.dto.EntidadFiscalRequest;
import com.greenfire.fiscal.dto.EntidadFiscalEdicionResponse;
import com.greenfire.fiscal.dto.EntidadFiscalResponse;
import com.greenfire.fiscal.dto.PuntoVentaEdicionRequest;
import com.greenfire.fiscal.dto.PuntoVentaRequest;
import com.greenfire.fiscal.dto.PuntoVentaResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/admin")
public class FiscalAdminController {

    private final EntidadFiscalService entidades;
    private final PuntoVentaService puntosVenta;

    public FiscalAdminController(EntidadFiscalService entidades, PuntoVentaService puntosVenta) {
        this.entidades = entidades;
        this.puntosVenta = puntosVenta;
    }

    // --- Entidades fiscales ---

    @GetMapping("/entidades-fiscales")
    public List<EntidadFiscalResponse> listarEntidades() {
        return entidades.listar();
    }

    @GetMapping("/entidades-fiscales/{id}")
    public EntidadFiscalResponse obtenerEntidad(@PathVariable Long id) {
        return entidades.obtener(id);
    }

    @PostMapping("/entidades-fiscales")
    @ResponseStatus(HttpStatus.CREATED)
    public EntidadFiscalResponse crearEntidad(@Valid @RequestBody EntidadFiscalRequest req) {
        return entidades.crear(req);
    }

    @PutMapping("/entidades-fiscales/{id}")
    public EntidadFiscalEdicionResponse editarEntidad(@PathVariable Long id, @Valid @RequestBody EntidadFiscalRequest req) {
        return entidades.editar(id, req);
    }

    @PostMapping("/entidades-fiscales/{id}/activar")
    public EntidadFiscalResponse activarEntidad(@PathVariable Long id) {
        return entidades.cambiarActivo(id, true);
    }

    @PostMapping("/entidades-fiscales/{id}/desactivar")
    public EntidadFiscalResponse desactivarEntidad(@PathVariable Long id) {
        return entidades.cambiarActivo(id, false);
    }

    // --- Puntos de venta ---

    @GetMapping("/puntos-venta")
    public List<PuntoVentaResponse> listarPuntosVenta(@RequestParam(required = false) Long ambitoId) {
        return puntosVenta.listar(ambitoId);
    }

    @GetMapping("/puntos-venta/{id}")
    public PuntoVentaResponse obtenerPuntoVenta(@PathVariable Long id) {
        return puntosVenta.obtener(id);
    }

    @PostMapping("/puntos-venta")
    @ResponseStatus(HttpStatus.CREATED)
    public PuntoVentaResponse crearPuntoVenta(@Valid @RequestBody PuntoVentaRequest req) {
        return puntosVenta.crear(req);
    }

    @PutMapping("/puntos-venta/{id}")
    public PuntoVentaResponse editarPuntoVenta(@PathVariable Long id, @Valid @RequestBody PuntoVentaEdicionRequest req) {
        return puntosVenta.editar(id, req);
    }

    @PostMapping("/puntos-venta/{id}/activar")
    public PuntoVentaResponse activarPuntoVenta(@PathVariable Long id) {
        return puntosVenta.cambiarActivo(id, true);
    }

    @PostMapping("/puntos-venta/{id}/desactivar")
    public PuntoVentaResponse desactivarPuntoVenta(@PathVariable Long id) {
        return puntosVenta.cambiarActivo(id, false);
    }
}
