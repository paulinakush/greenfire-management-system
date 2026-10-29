package com.greenfire;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;

@PruebaIntegracion
class FrontEstaticoTest {

    @Autowired
    MockMvc mvc;

    @Test
    void laRaizMuestraElSitioPublico() throws Exception {
        mvc.perform(get("/")).andExpect(forwardedUrl("/index.html"));
        mvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("Green Fire")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/privado/login.html", "/privado/shared/auth.js", "/assets/logos/logo-blanco.png"})
    void sirveLasPaginasYRecursosDelFront(String ruta) throws Exception {
        mvc.perform(get(ruta)).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/docs/complemento.md", "/backend/pom.xml", "/.env", "/CLAUDE.md", "/.git/config"})
    void noExponeArchivosFueraDelFront(String ruta) throws Exception {
        mvc.perform(get(ruta)).andExpect(status().isNotFound());
    }

    @Test
    void rechazaRutasConPuntoPunto() throws Exception {
        // El firewall de Spring Security corta el pedido antes de buscar el archivo.
        mvc.perform(get("/privado/../docs/complemento.md")).andExpect(status().isBadRequest());
    }
}
