package com.greenfire.comprobantes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.greenfire.Datos;
import com.greenfire.PruebaIntegracion;
import com.greenfire.ambitos.Ambito;
import com.greenfire.ambitos.AmbitoRepository;
import com.greenfire.ambitos.TipoAmbito;
import com.greenfire.catalogo.Articulo;
import com.greenfire.catalogo.Categoria;
import com.greenfire.catalogo.TipoArticulo;
import com.greenfire.clientes.Cliente;
import com.greenfire.comprobantes.EmisorComprobantes.Emision;
import com.greenfire.comprobantes.EmisorComprobantes.Renglon;
import com.greenfire.fiscal.TipoComprobante;
import com.greenfire.seguridad.Perfil;

import jakarta.persistence.EntityManager;

/**
 * Consulta de comprobantes por la API: qué ve cada perfil, la vista imprimible y que no haya alta directa. Los
 * comprobantes se emiten con {@link EmisorComprobantes}, como lo harán las ventas, las entregas y las cobranzas.
 */
@PruebaIntegracion
class ComprobanteApiTest {

    private static final ZoneId ZONA = ZoneId.of("America/Argentina/Buenos_Aires");

    @Autowired
    MockMvc mvc;
    @Autowired
    Datos datos;
    @Autowired
    EmisorComprobantes emisor;
    @Autowired
    AmbitoRepository ambitos;
    @Autowired
    EntityManager em;

    Ambito fabrica;
    Ambito centro;
    Ambito anexo; // otro local de la misma entidad fiscal que Centro
    Ambito norte;
    Articulo coco;
    Articulo libro;
    Cliente juan;
    Long usuario;

    Comprobante remitoFabrica;
    Comprobante ventaCentro;
    Comprobante reciboCentro;
    Comprobante ventaAnexo;
    Comprobante ventaNorte;

    @BeforeEach
    void datos() {
        fabrica = datos.fabrica(); // responsable inscripta
        centro = datos.local("Centro"); // monotributista
        norte = datos.local("Norte");
        anexo = new Ambito();
        anexo.setTipo(TipoAmbito.local);
        anexo.setNombre("Anexo");
        anexo.setEntidadFiscal(centro.getEntidadFiscal());
        ambitos.save(anexo);
        Categoria categoria = datos.categoria("Sustratos");
        coco = datos.articulo(fabrica, TipoArticulo.producto_terminado, "Coco", categoria);
        libro = datos.articulo(fabrica, TipoArticulo.producto_terminado, "Libro", categoria);
        libro.setAlicuotaIva(new BigDecimal("10.5"));
        juan = datos.cliente(centro, "Juan <b>Pérez</b> & Cía");
        usuario = datos.usuario("Ana").getId();

        datos.puntoVentaX(fabrica);
        datos.puntoVentaX(centro);
        datos.puntoVentaX(anexo, 2, TipoComprobante.comprobante_venta);
        datos.puntoVentaX(norte);

        remitoFabrica = emisor.emitir(new Emision(fabrica, TipoComprobante.remito, datos.clienteDelLocal(centro),
                false, List.of(Renglon.de(coco, new BigDecimal("2"), new BigDecimal("1000")),
                        Renglon.de(libro, BigDecimal.ONE, new BigDecimal("200"))), usuario));
        ventaCentro = emisor.emitir(new Emision(centro, TipoComprobante.comprobante_venta, juan, true,
                List.of(Renglon.de(coco, new BigDecimal("3"), new BigDecimal("1210"))), usuario));
        reciboCentro = emisor.emitir(new Emision(centro, TipoComprobante.recibo, juan, true,
                List.of(Renglon.libre("Pago a cuenta", new BigDecimal("500"), BigDecimal.ZERO)), usuario));
        ventaAnexo = emisor.emitir(new Emision(anexo, TipoComprobante.comprobante_venta, null, true,
                List.of(Renglon.de(coco, BigDecimal.ONE, new BigDecimal("1210"))), usuario));
        ventaNorte = emisor.emitir(new Emision(norte, TipoComprobante.comprobante_venta, null, true,
                List.of(Renglon.de(coco, BigDecimal.ONE, new BigDecimal("1210"))), usuario));
        em.flush();
        em.clear();
    }

    // --- Quién ve qué ---

    @Test
    void cadaDuenoVeLosComprobantesDeLaEntidadDeSuAmbito() throws Exception {
        verLista(datos.en(fabrica, Perfil.dueno_fabrica), remitoFabrica);
        // Centro y Anexo comparten entidad fiscal: su dueño ve los de los dos
        verLista(datos.en(centro, Perfil.dueno_local), ventaCentro, reciboCentro, ventaAnexo);
        verLista(datos.en(norte, Perfil.dueno_local), ventaNorte);
    }

    @Test
    void elEmpleadoVeEImprimeSoloLosDeSuLocal() throws Exception {
        RequestPostProcessor empleado = datos.en(centro, Perfil.empleado_local);

        verLista(empleado, ventaCentro, reciboCentro);
        verLista(datos.en(anexo, Perfil.empleado_local), ventaAnexo);
        mvc.perform(get("/api/comprobantes/" + ventaCentro.getId()).with(empleado)).andExpect(status().isOk());
        mvc.perform(get("/api/comprobantes/" + ventaCentro.getId() + "/imprimible").with(empleado))
                .andExpect(status().isOk());
        // el de otro local, aunque sea de la misma entidad, no existe para él
        mvc.perform(get("/api/comprobantes/" + ventaAnexo.getId()).with(empleado)).andExpect(status().isNotFound());
        mvc.perform(get("/api/comprobantes/" + ventaAnexo.getId() + "/imprimible").with(empleado))
                .andExpect(status().isNotFound());
    }

    @Test
    void nadieVeLosComprobantesDeOtraEntidad() throws Exception {
        RequestPostProcessor duenoCentro = datos.en(centro, Perfil.dueno_local);
        RequestPostProcessor duenoFabrica = datos.en(fabrica, Perfil.dueno_fabrica);
        RequestPostProcessor empleadoNorte = datos.en(norte, Perfil.empleado_local);

        for (String ruta : List.of("", "/imprimible")) {
            mvc.perform(get("/api/comprobantes/" + ventaNorte.getId() + ruta).with(duenoCentro))
                    .andExpect(status().isNotFound());
            mvc.perform(get("/api/comprobantes/" + remitoFabrica.getId() + ruta).with(duenoCentro))
                    .andExpect(status().isNotFound());
            mvc.perform(get("/api/comprobantes/" + ventaCentro.getId() + ruta).with(duenoFabrica))
                    .andExpect(status().isNotFound());
            mvc.perform(get("/api/comprobantes/" + ventaCentro.getId() + ruta).with(empleadoNorte))
                    .andExpect(status().isNotFound());
            mvc.perform(get("/api/comprobantes/999999999" + ruta).with(duenoCentro)).andExpect(status().isNotFound());
        }
        // filtrar por un cliente de otro local no muestra nada
        mvc.perform(get("/api/comprobantes").param("clienteId", juan.getId().toString()).with(duenoFabrica))
                .andExpect(status().isOk()).andExpect(jsonPath("$.comprobantes", empty()));
    }

    @Test
    void elAdministradorTecnicoNoVeComprobantesYSinSesionEs401() throws Exception {
        RequestPostProcessor admin = datos.admin();

        mvc.perform(get("/api/comprobantes").with(admin)).andExpect(status().isForbidden());
        mvc.perform(get("/api/comprobantes/" + ventaCentro.getId()).with(admin)).andExpect(status().isForbidden());
        mvc.perform(get("/api/comprobantes/" + ventaCentro.getId() + "/imprimible").with(admin))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/comprobantes")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/comprobantes/" + ventaCentro.getId() + "/imprimible"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void noHayAltaNiModificacionDirectaDeComprobantes() throws Exception {
        RequestPostProcessor dueno = datos.en(centro, Perfil.dueno_local);

        mvc.perform(post("/api/comprobantes").with(dueno).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isMethodNotAllowed());
        mvc.perform(put("/api/comprobantes/" + ventaCentro.getId()).with(dueno)
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isMethodNotAllowed());
        mvc.perform(delete("/api/comprobantes/" + ventaCentro.getId()).with(dueno))
                .andExpect(status().isMethodNotAllowed());
    }

    // --- Filtros ---

    @Test
    void filtraPorTipoFechasYCliente() throws Exception {
        RequestPostProcessor dueno = datos.en(centro, Perfil.dueno_local);
        LocalDate hoy = LocalDate.now(ZONA);

        verLista(dueno, "tipo", "recibo", reciboCentro);
        verLista(dueno, "clienteId", juan.getId().toString(), ventaCentro, reciboCentro);
        verLista(dueno, "desde", hoy.toString(), ventaCentro, reciboCentro, ventaAnexo);
        verLista(dueno, "hasta", hoy.toString(), ventaCentro, reciboCentro, ventaAnexo);
        verLista(dueno, "desde", hoy.plusDays(1).toString());
        verLista(dueno, "hasta", hoy.minusDays(1).toString());
        mvc.perform(get("/api/comprobantes").param("tamano", "2").param("pagina", "1").with(dueno))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.comprobantes.length()").value(1))
                .andExpect(jsonPath("$.total").value(3));
        mvc.perform(get("/api/comprobantes").param("tipo", "otro").with(dueno)).andExpect(status().isBadRequest());
    }

    // --- Detalle ---

    @Test
    void elDetalleMuestraElIvaDiscriminadoSoloSiElEmisorLoDiscrimina() throws Exception {
        mvc.perform(get("/api/comprobantes/" + remitoFabrica.getId()).with(datos.en(fabrica, Perfil.dueno_fabrica)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tipo").value("remito"))
                .andExpect(jsonPath("$.letra").value("X"))
                .andExpect(jsonPath("$.leyenda").value("Documento no válido como factura"))
                .andExpect(jsonPath("$.numeroCompleto").value("00001-00000001"))
                .andExpect(jsonPath("$.emisor.condicionIva").value("responsable_inscripto"))
                .andExpect(jsonPath("$.receptor.razonSocial").value("Entidad monotributo"))
                .andExpect(jsonPath("$.receptor.documentoTipo").value("cuit"))
                .andExpect(jsonPath("$.discriminaIva").value(true))
                .andExpect(jsonPath("$.preciosConIva").value(false))
                .andExpect(jsonPath("$.neto").value(2200.00))
                .andExpect(jsonPath("$.iva").value(441.00)) // 420 del 21 % y 21 del 10,5 %
                .andExpect(jsonPath("$.total").value(2641.00))
                .andExpect(jsonPath("$.ivaPorAlicuota[*].iva").value(containsInAnyOrder(21.00, 420.00)))
                .andExpect(jsonPath("$.renglones.length()").value(2))
                .andExpect(jsonPath("$.anulacion").doesNotExist());

        String json = mvc.perform(get("/api/comprobantes/" + ventaCentro.getId())
                        .with(datos.en(centro, Perfil.empleado_local)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.discriminaIva").value(false))
                .andExpect(jsonPath("$.ivaPorAlicuota", empty()))
                .andExpect(jsonPath("$.iva").value(0))
                .andExpect(jsonPath("$.total").value(3630.00))
                .andExpect(jsonPath("$.receptor.razonSocial").value("Juan <b>Pérez</b> & Cía"))
                .andReturn().getResponse().getContentAsString();
        assertThat(json.toLowerCase()).doesNotContain("costo", "margen");
    }

    @Test
    void elDetalleDeUnAnuladoDiceCuandoYPorQue() throws Exception {
        Comprobante c = em.find(Comprobante.class, ventaCentro.getId());
        emisor.anular(c, "Venta anulada", usuario);
        em.flush();
        em.clear();

        mvc.perform(get("/api/comprobantes/" + ventaCentro.getId()).with(datos.en(centro, Perfil.dueno_local)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("anulado"))
                .andExpect(jsonPath("$.anulacion.motivo").value("Venta anulada"))
                .andExpect(jsonPath("$.anulacion.usuario").value("Ana"));
        String html = imprimible(ventaCentro, datos.en(centro, Perfil.dueno_local));
        assertThat(html).contains("ANULADO el ", "Motivo: Venta anulada");
    }

    // --- Vista imprimible ---

    @Test
    void laVistaImprimibleDeUnResponsableInscriptoDiscriminaElIva() throws Exception {
        String html = imprimible(remitoFabrica, datos.en(fabrica, Perfil.dueno_fabrica));

        assertThat(html).startsWith("<!doctype html>")
                .contains("<title>Remito X 00001-00000001</title>")
                .contains(">X</div>", "Documento no válido como factura", "IVA Responsable Inscripto")
                .contains("Precio unit. (sin IVA)", "Neto gravado", "IVA 10,5 %", "IVA 21 %")
                .contains("$ 2.200,00", "$ 420,00", "$ 21,00", "$ 2.641,00")
                .doesNotContain("<script", "costo");
    }

    @Test
    void laVistaImprimibleDeUnMonotributistaMuestraSoloElTotalYEscapaLosTextos() throws Exception {
        String html = imprimible(ventaCentro, datos.en(centro, Perfil.empleado_local));

        assertThat(html).contains("Comprobante de venta", "00001-00000001", "Documento no válido como factura",
                        "Responsable Monotributo", "$ 3.630,00")
                .contains("Juan &lt;b&gt;Pérez&lt;/b&gt; &amp; Cía")
                .doesNotContain("<b>Pérez</b>", "Neto gravado", "IVA 21", "(con IVA)", "costo");
    }

    // --- Ayudas ---

    private String imprimible(Comprobante c, RequestPostProcessor sesion) throws Exception {
        return mvc.perform(get("/api/comprobantes/" + c.getId() + "/imprimible").with(sesion))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().encoding("UTF-8"))
                .andExpect(header().string("Cache-Control", "no-store"))
                .andReturn().getResponse().getContentAsString();
    }

    private void verLista(RequestPostProcessor sesion, Comprobante... esperados) throws Exception {
        verLista(sesion, null, null, esperados);
    }

    private void verLista(RequestPostProcessor sesion, String filtro, String valor, Comprobante... esperados)
            throws Exception {
        var pedido = get("/api/comprobantes").with(sesion);
        if (filtro != null) {
            pedido.param(filtro, valor);
        }
        Object[] ids = Arrays.stream(esperados).map(c -> c.getId().intValue()).toArray();
        mvc.perform(pedido)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(esperados.length))
                .andExpect(jsonPath("$.comprobantes[*].id").value(containsInAnyOrder(ids)));
    }
}
