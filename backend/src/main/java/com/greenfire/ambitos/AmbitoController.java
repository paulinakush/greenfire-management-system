package com.greenfire.ambitos;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.greenfire.ambitos.dto.AmbitoResponse;
import com.greenfire.ambitos.dto.ContactoLocalRequest;
import com.greenfire.ambitos.dto.LocalPublicoResponse;
import com.greenfire.comun.CachePublico;

import jakarta.validation.Valid;

@RestController
public class AmbitoController {

    private final AmbitoService service;

    public AmbitoController(AmbitoService service) {
        this.service = service;
    }

    /** El ámbito en el que está trabajando el usuario. */
    @GetMapping("/api/ambito")
    public AmbitoResponse actual() {
        return service.actual();
    }

    @PutMapping("/api/ambito/contacto")
    public AmbitoResponse editarContacto(@Valid @RequestBody ContactoLocalRequest req) {
        return service.editarContacto(req);
    }

    @GetMapping("/api/publico/locales")
    public ResponseEntity<List<LocalPublicoResponse>> localesPublicos() {
        return ResponseEntity.ok().cacheControl(CachePublico.CORTO).body(service.localesPublicos());
    }
}
