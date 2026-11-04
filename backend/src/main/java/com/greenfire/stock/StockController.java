package com.greenfire.stock;

import java.time.LocalDate;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.greenfire.catalogo.TipoArticulo;
import com.greenfire.stock.dto.AjusteRequest;
import com.greenfire.stock.dto.AjusteResponse;
import com.greenfire.stock.dto.LimitesRequest;
import com.greenfire.stock.dto.PaginaMovimientos;
import com.greenfire.stock.dto.StockResponse;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/stock")
public class StockController {

    private final StockService service;

    public StockController(StockService service) {
        this.service = service;
    }

    @GetMapping
    public List<StockResponse> consultar(@RequestParam(required = false) Long ubicacionId,
            @RequestParam(required = false) Long articuloId, @RequestParam(required = false) TipoArticulo tipo,
            @RequestParam(defaultValue = "false") boolean bajoMinimo) {
        return service.consultar(ubicacionId, articuloId, tipo, bajoMinimo);
    }

    @GetMapping("/alertas")
    public List<StockResponse> alertas() {
        return service.alertas();
    }

    @PutMapping("/limites")
    public StockResponse editarLimites(@Valid @RequestBody LimitesRequest req) {
        return service.editarLimites(req);
    }

    @PostMapping("/ajustes")
    @ResponseStatus(HttpStatus.CREATED)
    public AjusteResponse ajustar(@Valid @RequestBody AjusteRequest req) {
        return service.ajustar(req);
    }

    @GetMapping("/movimientos")
    public PaginaMovimientos movimientos(@RequestParam(required = false) Long articuloId,
            @RequestParam(required = false) Long ubicacionId, @RequestParam(required = false) TipoMovimientoStock tipo,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
            @RequestParam(defaultValue = "0") int pagina, @RequestParam(defaultValue = "50") int tamano) {
        return service.movimientos(articuloId, ubicacionId, tipo, desde, hasta, pagina, tamano);
    }
}
