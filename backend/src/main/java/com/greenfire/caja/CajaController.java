package com.greenfire.caja;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.greenfire.caja.dto.AperturaRequest;
import com.greenfire.caja.dto.CierreRequest;
import com.greenfire.caja.dto.CorreccionRequest;
import com.greenfire.caja.dto.MovimientoCajaResponse;
import com.greenfire.caja.dto.MovimientoRequest;
import com.greenfire.caja.dto.PaginaSesionesCaja;
import com.greenfire.caja.dto.SesionCajaResponse;
import com.greenfire.caja.dto.SesionCajaVista;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/caja")
public class CajaController {

    private final CajaService service;

    public CajaController(CajaService service) {
        this.service = service;
    }

    @PostMapping("/abrir")
    @ResponseStatus(HttpStatus.CREATED)
    public SesionCajaVista abrir(@Valid @RequestBody AperturaRequest req) {
        return service.abrir(req);
    }

    /** La sesión abierta del local; 204 si no hay ninguna. */
    @GetMapping("/actual")
    public ResponseEntity<SesionCajaVista> actual() {
        return service.actual().map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/movimientos")
    @ResponseStatus(HttpStatus.CREATED)
    public MovimientoCajaResponse registrarMovimiento(@Valid @RequestBody MovimientoRequest req) {
        return service.registrarMovimiento(req);
    }

    @PostMapping("/cerrar")
    public SesionCajaVista cerrar(@Valid @RequestBody CierreRequest req) {
        return service.cerrar(req);
    }

    @GetMapping("/sesiones")
    public PaginaSesionesCaja listar(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
            @RequestParam(defaultValue = "0") int pagina, @RequestParam(defaultValue = "50") int tamano) {
        return service.listar(desde, hasta, pagina, tamano);
    }

    @GetMapping("/sesiones/{id}")
    public SesionCajaVista obtener(@PathVariable Long id) {
        return service.obtener(id);
    }

    @PostMapping("/sesiones/{id}/correccion")
    public SesionCajaResponse corregir(@PathVariable Long id, @Valid @RequestBody CorreccionRequest req) {
        return service.corregir(id, req);
    }
}
