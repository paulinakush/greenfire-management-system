package com.greenfire.auditoria;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class RedactorTest {

    @Test
    void ocultaContrasenasHashesTokensYCertificadoAunqueEstenAnidados() {
        Map<String, Object> edicion = new HashMap<>();
        edicion.put("antes", "vault://viejo");
        edicion.put("despues", "vault://nuevo");
        Map<String, Object> detalle = Map.of(
                "password", "secreta-123",
                "passwordHash", "$2a$10$abc",
                "resetTokenHash", "abc123",
                "arcaCertificadoRef", edicion,
                "arcaCertificadoVence", Map.of("antes", "2026-01-01", "despues", "2027-01-01"),
                "anidado", Map.of("token", "xyz", "nombre", "Ana"),
                "lista", List.of(Map.of("contrasena", "otra")));

        Object limpio = Redactor.limpiar(detalle);

        assertThat(limpio.toString())
                .doesNotContain("secreta-123", "$2a$10$abc", "abc123", "vault://", "xyz", "otra", "2027-01-01")
                .contains("Ana");
        assertThat(((Map<?, ?>) limpio).get("arcaCertificadoRef"))
                .isEqualTo(Map.of("antes", Redactor.OCULTO, "despues", Redactor.OCULTO));
    }

    @Test
    void dejaIntactosLosCamposComunes() {
        Map<String, Object> detalle = Map.of("nombre", Map.of("antes", "Centro", "despues", "Local Centro"),
                "cuit", "20123456786");

        assertThat(Redactor.limpiar(detalle)).isEqualTo(detalle);
    }
}
