package com.greenfire.fiscal;

import static com.greenfire.Datos.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.greenfire.Datos;
import com.greenfire.PruebaIntegracion;
import com.greenfire.ambitos.Ambito;
import com.greenfire.ambitos.TipoAmbito;
import com.greenfire.auditoria.EventoAuditoria;
import com.greenfire.seguridad.Perfil;

@PruebaIntegracion
class PuntoVentaApiTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    Datos datos;
    @Autowired
    ApplicationEvents eventos;

    @Test
    void elAdminCreaUnPuntoDeVentaQueTomaLaEntidadFiscalDeSuAmbito() throws Exception {
        Ambito fabrica = datos.ambito(TipoAmbito.fabrica, "Fábrica", CondicionIva.responsable_inscripto);

        mvc.perform(json(post("/api/admin/puntos-venta"), datos.admin(), cuerpo(fabrica.getId(), 1,
                "{\"tipo\":\"factura\",\"letra\":\"A\"},{\"tipo\":\"factura\",\"letra\":\"B\"},"
                        + "{\"tipo\":\"remito\",\"letra\":\"X\"}")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.entidadFiscalId").value(fabrica.getEntidadFiscal().getId()))
                .andExpect(jsonPath("$.comprobantes[*].letra").value(containsInAnyOrder("A", "B", "X")));
    }

    @Test
    void unMonotributistaNoPuedeEmitirFacturaA() throws Exception {
        Ambito local = datos.local("Centro");

        mvc.perform(json(post("/api/admin/puntos-venta"), datos.admin(),
                cuerpo(local.getId(), 1, "{\"tipo\":\"factura\",\"letra\":\"A\"}")))
                .andExpect(status().isConflict());
        mvc.perform(json(post("/api/admin/puntos-venta"), datos.admin(),
                cuerpo(local.getId(), 1, "{\"tipo\":\"factura\",\"letra\":\"C\"}")))
                .andExpect(status().isCreated());
    }

    @Test
    void elNumeroEsUnicoDentroDeCadaEntidadFiscal() throws Exception {
        Ambito centro = datos.local("Centro");
        Ambito norte = datos.local("Norte");

        mvc.perform(json(post("/api/admin/puntos-venta"), datos.admin(), cuerpo(centro.getId(), 2, "")))
                .andExpect(status().isCreated());
        mvc.perform(json(post("/api/admin/puntos-venta"), datos.admin(), cuerpo(centro.getId(), 2, "")))
                .andExpect(status().isConflict());
        mvc.perform(json(post("/api/admin/puntos-venta"), datos.admin(), cuerpo(norte.getId(), 2, "")))
                .andExpect(status().isCreated());
    }

    @Test
    void laEdicionDeComprobantesQuedaEnAuditoriaConAntesYDespues() throws Exception {
        Ambito local = datos.local("Centro");
        String creado = mvc.perform(json(post("/api/admin/puntos-venta"), datos.admin(),
                cuerpo(local.getId(), 3, "{\"tipo\":\"factura\",\"letra\":\"C\"}")))
                .andReturn().getResponse().getContentAsString();
        long id = Long.parseLong(creado.replaceAll(".*\"id\":(\\d+).*", "$1"));

        mvc.perform(json(put("/api/admin/puntos-venta/" + id), datos.admin(), """
                {"numero": 3, "comprobantes": [{"tipo":"factura","letra":"C"},{"tipo":"recibo","letra":"C"}]}
                """))
                .andExpect(status().isOk());

        EventoAuditoria edicion = eventos.stream(EventoAuditoria.class)
                .filter(e -> e.accion().equals("editar")).findFirst().orElseThrow();
        assertThat(edicion.detalle()).containsOnlyKeys("comprobantes");
        assertThat(edicion.detalle().get("comprobantes")).isEqualTo(
                Map.of("antes", Set.of("factura C"), "despues", Set.of("factura C", "recibo C")));
    }

    @Test
    void soloElAdminGestionaPuntosDeVenta() throws Exception {
        Ambito local = datos.local("Centro");
        RequestPostProcessor dueno = datos.en(local, Perfil.dueno_local);

        mvc.perform(get("/api/admin/puntos-venta").with(dueno))
                .andExpect(status().isForbidden());
        mvc.perform(json(post("/api/admin/puntos-venta"), dueno, cuerpo(local.getId(), 1, "")))
                .andExpect(status().isForbidden());
    }

    private static String cuerpo(Long ambitoId, int numero, String comprobantes) {
        return """
                {"ambitoId": %d, "numero": %d, "comprobantes": [%s]}
                """.formatted(ambitoId, numero, comprobantes);
    }
}
