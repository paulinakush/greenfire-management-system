package com.greenfire.fiscal;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.greenfire.fiscal.dto.EntidadFiscalLocalResponse;

@RestController
public class FiscalController {

    private final EntidadFiscalService entidades;

    public FiscalController(EntidadFiscalService entidades) {
        this.entidades = entidades;
    }

    /** Solo lectura, para el dueño de local. */
    @GetMapping("/api/ambito/entidad-fiscal")
    public EntidadFiscalLocalResponse deMiLocal() {
        return entidades.deMiLocal();
    }
}
