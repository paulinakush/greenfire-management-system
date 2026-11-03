package com.greenfire.fiscal;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import com.greenfire.Datos;
import com.greenfire.PruebaIntegracion;
import com.greenfire.ambitos.Ambito;
import com.greenfire.seguridad.Perfil;

@PruebaIntegracion
class EntidadFiscalLocalApiTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    Datos datos;
    @Autowired
    PuntoVentaRepository puntosVenta;

    @Test
    void elDuenoDeLocalVeLaEntidadFiscalDeSuLocalSinElCertificado() throws Exception {
        Ambito local = datos.local("Centro");
        local.getEntidadFiscal().setArcaCertificadoRef("vault://certs/centro");
        local.getEntidadFiscal().setArcaCertificadoVence(LocalDate.of(2027, 1, 1));
        PuntoVenta pv = new PuntoVenta();
        pv.setAmbito(local);
        pv.setNumero(4);
        pv.setComprobantes(Set.of(new ComprobantePermitido(TipoComprobante.factura, LetraComprobante.C)));
        puntosVenta.save(pv);

        mvc.perform(get("/api/ambito/entidad-fiscal").with(datos.en(local, Perfil.dueno_local)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cuit").value(local.getEntidadFiscal().getCuit()))
                .andExpect(jsonPath("$.condicionIva").value("monotributo"))
                .andExpect(jsonPath("$.puntosVenta[0].numero").value(4))
                .andExpect(jsonPath("$.puntosVenta[0].comprobantes[0]").value("factura C"))
                .andExpect(jsonPath("$.arcaCertificadoRef").doesNotExist())
                .andExpect(jsonPath("$.arcaCertificadoVence").doesNotExist());
    }

    @Test
    void elEmpleadoYElDuenoDeFabricaNoLaVen() throws Exception {
        Ambito local = datos.local("Centro");
        Ambito fabrica = datos.fabrica();

        mvc.perform(get("/api/ambito/entidad-fiscal").with(datos.en(local, Perfil.empleado_local)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/ambito/entidad-fiscal").with(datos.en(fabrica, Perfil.dueno_fabrica)))
                .andExpect(status().isForbidden());
    }
}
