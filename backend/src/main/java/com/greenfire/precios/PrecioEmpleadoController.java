package com.greenfire.precios;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.greenfire.precios.dto.PrecioEmpleadoResponse;

/** Precios de venta del local, sin costos ni márgenes. */
@RestController
public class PrecioEmpleadoController {

    private final ListaPreciosService service;

    public PrecioEmpleadoController(ListaPreciosService service) {
        this.service = service;
    }

    @GetMapping("/api/precios")
    public List<PrecioEmpleadoResponse> precios() {
        return service.preciosDeVenta();
    }
}
