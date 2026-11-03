package com.greenfire.precios;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.greenfire.precios.dto.ActualizacionMasivaRequest;
import com.greenfire.precios.dto.ActualizacionMasivaResponse;
import com.greenfire.precios.dto.ListaPreciosItemResponse;
import com.greenfire.precios.dto.ListaPreciosRequest;
import com.greenfire.precios.dto.ListaPreciosResponse;
import com.greenfire.precios.dto.PaginaHistorial;
import com.greenfire.precios.dto.PrecioRequest;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/listas-precios")
public class ListaPreciosController {

    private final ListaPreciosService service;
    private final ActualizacionMasivaService actualizacionMasiva;

    public ListaPreciosController(ListaPreciosService service, ActualizacionMasivaService actualizacionMasiva) {
        this.service = service;
        this.actualizacionMasiva = actualizacionMasiva;
    }

    @GetMapping
    public List<ListaPreciosResponse> listar() {
        return service.listar();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ListaPreciosResponse crear(@Valid @RequestBody ListaPreciosRequest req) {
        return service.crear(req);
    }

    @PutMapping("/{id}")
    public ListaPreciosResponse editar(@PathVariable Long id, @Valid @RequestBody ListaPreciosRequest req) {
        return service.editar(id, req);
    }

    @PostMapping("/{id}/activar")
    public ListaPreciosResponse activar(@PathVariable Long id) {
        return service.cambiarActivo(id, true);
    }

    @PostMapping("/{id}/desactivar")
    public ListaPreciosResponse desactivar(@PathVariable Long id) {
        return service.cambiarActivo(id, false);
    }

    @GetMapping("/{id}/items")
    public List<ListaPreciosItemResponse> items(@PathVariable Long id) {
        return service.items(id);
    }

    @PutMapping("/{id}/items/{articuloId}")
    public ListaPreciosItemResponse ponerPrecio(@PathVariable Long id, @PathVariable Long articuloId,
            @Valid @RequestBody PrecioRequest req) {
        return service.ponerPrecio(id, articuloId, req);
    }

    @DeleteMapping("/{id}/items/{articuloId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void quitarPrecio(@PathVariable Long id, @PathVariable Long articuloId) {
        service.quitarPrecio(id, articuloId);
    }

    @PostMapping("/{id}/actualizacion-masiva")
    public ActualizacionMasivaResponse actualizacionMasiva(@PathVariable Long id,
            @Valid @RequestBody ActualizacionMasivaRequest req) {
        return actualizacionMasiva.aplicar(id, req);
    }

    @GetMapping("/{id}/historial")
    public PaginaHistorial historial(@PathVariable Long id, @RequestParam(required = false) Long articuloId,
            @RequestParam(defaultValue = "0") int pagina, @RequestParam(defaultValue = "50") int tamano) {
        return service.historial(id, articuloId, pagina, tamano);
    }
}
