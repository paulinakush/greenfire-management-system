package com.greenfire.comprobantes;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.greenfire.comprobantes.dto.ComprobanteResponse;
import com.greenfire.comprobantes.dto.PaginaComprobantes;
import com.greenfire.fiscal.TipoComprobante;

/** Solo consulta: no hay alta directa de comprobantes. Los emiten las ventas, las entregas y las cobranzas. */
@RestController
@RequestMapping("/api/comprobantes")
public class ComprobanteController {

    private static final MediaType HTML = new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8);

    private final ComprobanteService service;

    public ComprobanteController(ComprobanteService service) {
        this.service = service;
    }

    @GetMapping
    public PaginaComprobantes listar(@RequestParam(required = false) TipoComprobante tipo,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate desde,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate hasta,
            @RequestParam(required = false) Long clienteId, @RequestParam(defaultValue = "0") int pagina,
            @RequestParam(defaultValue = "50") int tamano) {
        return service.listar(tipo, desde, hasta, clienteId, pagina, tamano);
    }

    @GetMapping("/{id}")
    public ComprobanteResponse obtener(@PathVariable Long id) {
        return service.obtener(id);
    }

    /** Vista para imprimir o guardar como PDF desde el navegador. No se guarda en caché. */
    @GetMapping("/{id}/imprimible")
    public ResponseEntity<String> imprimible(@PathVariable Long id) {
        return ResponseEntity.ok().contentType(HTML).cacheControl(CacheControl.noStore()).body(service.imprimible(id));
    }
}
