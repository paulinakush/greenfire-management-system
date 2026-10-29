package com.greenfire.fiscal;

import static com.greenfire.Datos.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasKey;
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
class EntidadFiscalApiTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    Datos datos;
    @Autowired
    ApplicationEvents eventos;
    @Autowired
    PuntoVentaRepository puntosVenta;

    @Test
    void sinSesionResponde401() throws Exception {
        mvc.perform(get("/api/admin/entidades-fiscales")).andExpect(status().isUnauthorized());
    }

    @Test
    void unDuenoNoPuedeVerNiEditarEntidadesFiscales() throws Exception {
        Ambito local = datos.local("Centro");
        RequestPostProcessor dueno = datos.en(local, Perfil.dueno_local);

        mvc.perform(get("/api/admin/entidades-fiscales").with(dueno))
                .andExpect(status().isForbidden());
        mvc.perform(json(put("/api/admin/entidades-fiscales/" + local.getEntidadFiscal().getId()), dueno,
                cuerpo("Otra", Datos.cuit(), "monotributo")))
                .andExpect(status().isForbidden());
    }

    @Test
    void elAdminCreaUnaEntidadYQuedaEnAuditoria() throws Exception {
        String cuit = Datos.cuit();

        mvc.perform(json(post("/api/admin/entidades-fiscales"), datos.admin(),
                cuerpo("Green Fire SRL", cuit, "responsable_inscripto")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.cuit").value(cuit))
                .andExpect(jsonPath("$.activo").value(true));

        assertThat(eventos.stream(EventoAuditoria.class))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.accion()).isEqualTo("crear");
                    assertThat(e.entidad()).isEqualTo("entidad_fiscal");
                    assertThat(e.detalle().get("cuit")).isEqualTo(cuit);
                });
    }

    @Test
    void rechazaUnCuitInvalido() throws Exception {
        mvc.perform(json(post("/api/admin/entidades-fiscales"), datos.admin(),
                cuerpo("Green Fire SRL", "20123456787", "responsable_inscripto")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.campos", hasKey("cuit")));
    }

    @Test
    void rechazaUnCuitRepetido() throws Exception {
        EntidadFiscal existente = datos.entidad(CondicionIva.monotributo);

        mvc.perform(json(post("/api/admin/entidades-fiscales"), datos.admin(),
                cuerpo("Otra", existente.getCuit(), "monotributo")))
                .andExpect(status().isConflict());
    }

    @Test
    void laEdicionRegistraSoloLosCamposQueCambiaronConSuValorAnteriorYNuevo() throws Exception {
        EntidadFiscal e = datos.entidad(CondicionIva.monotributo);

        mvc.perform(json(put("/api/admin/entidades-fiscales/" + e.getId()), datos.admin(),
                cuerpo("Nueva razón social", e.getCuit(), "monotributo")))
                .andExpect(status().isOk());

        EventoAuditoria evento = eventos.stream(EventoAuditoria.class).findFirst().orElseThrow();
        assertThat(evento.accion()).isEqualTo("editar");
        assertThat(evento.entidadId()).isEqualTo(e.getId());
        assertThat(evento.detalle()).containsOnlyKeys("razonSocial");
        assertThat(evento.detalle().get("razonSocial"))
                .isEqualTo(Map.of("antes", "Entidad monotributo", "despues", "Nueva razón social"));
    }

    @Test
    void noPermiteCambiarLaCondicionDeIvaSiDejaPuntosDeVentaConLetrasInvalidas() throws Exception {
        Ambito fabrica = datos.ambito(TipoAmbito.fabrica, "Fábrica", CondicionIva.responsable_inscripto);
        PuntoVenta pv = new PuntoVenta();
        pv.setAmbito(fabrica);
        pv.setNumero(1);
        pv.setComprobantes(Set.of(new ComprobantePermitido(TipoComprobante.factura, LetraComprobante.A)));
        puntosVenta.save(pv);
        EntidadFiscal e = fabrica.getEntidadFiscal();

        mvc.perform(json(put("/api/admin/entidades-fiscales/" + e.getId()), datos.admin(),
                cuerpo(e.getRazonSocial(), e.getCuit(), "monotributo")))
                .andExpect(status().isConflict());
    }

    private static String cuerpo(String razonSocial, String cuit, String condicion) {
        return """
                {"razonSocial": "%s", "cuit": "%s", "condicionIva": "%s"}
                """.formatted(razonSocial, cuit, condicion);
    }
}
