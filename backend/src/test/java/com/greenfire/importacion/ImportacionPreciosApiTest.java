package com.greenfire.importacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.greenfire.Datos;
import com.greenfire.PruebaIntegracion;
import com.greenfire.ambitos.Ambito;
import com.greenfire.auditoria.EventoAuditoria;
import com.greenfire.catalogo.Articulo;
import com.greenfire.catalogo.Categoria;
import com.greenfire.catalogo.TipoArticulo;
import com.greenfire.precios.ListaPrecios;
import com.greenfire.seguridad.Perfil;
import com.greenfire.seguridad.Sesion;
import com.greenfire.usuarios.Usuario;

import jakarta.persistence.EntityManager;

/** Importación CSV de precios a una lista del propio ámbito. */
@PruebaIntegracion
class ImportacionPreciosApiTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    Datos datos;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ApplicationEvents eventos;
    @Autowired
    EntityManager em;

    Ambito fabrica;
    Ambito centro;
    Ambito norte;
    Articulo coco;
    Articulo perlita;
    Articulo maceta;
    Articulo macetaNorte;
    ListaPrecios lista;
    Usuario dueno;
    RequestPostProcessor duenoCentro;

    @BeforeEach
    void datos() {
        fabrica = datos.fabrica();
        centro = datos.local("Centro");
        norte = datos.local("Norte");
        Categoria categoria = datos.categoria("Sustratos");
        coco = datos.articulo(fabrica, TipoArticulo.producto_terminado, "Coco", categoria);
        perlita = datos.articulo(fabrica, TipoArticulo.insumo, "Perlita", null);
        maceta = datos.articulo(centro, TipoArticulo.reventa, "Maceta", categoria);
        macetaNorte = datos.articulo(norte, TipoArticulo.reventa, "Regadera", categoria);
        lista = datos.lista(centro, "Público", true, true);
        datos.precio(lista, maceta, "1800");
        dueno = datos.usuario("Dueña Centro");
        datos.perfil(dueno, centro, Perfil.dueno_local);
        duenoCentro = datos.sesion(Sesion.enAmbito(dueno.getId(), centro.getId(), Perfil.dueno_local));
        em.flush();
    }

    @Test
    void unArchivoValidoCargaLosPreciosYDejaHistorialConElUsuario() throws Exception {
        String csv = "articulo;precio\ncoco;1500,50\nMACETA;2000\n";

        vistaPrevia(csv).andExpect(status().isOk())
                .andExpect(jsonPath("$.crear").value(1))
                .andExpect(jsonPath("$.actualizar").value(1))
                .andExpect(jsonPath("$.detalle[0].nombre").value("Coco"))
                .andExpect(jsonPath("$.detalle[0].precioNuevo").value(1500.50))
                .andExpect(jsonPath("$.detalle[1].precioAnterior").value(1800.00))
                .andExpect(jsonPath("$.detalle[1].precioNuevo").value(2000.00));

        importar(lista.getId(), csv).andExpect(status().isOk()).andExpect(jsonPath("$.aplicado").value(true));

        em.flush();
        assertThat(precio(coco)).isEqualByComparingTo("1500.50");
        assertThat(precio(maceta)).isEqualByComparingTo("2000");
        List<Map<String, Object>> historial = jdbc.queryForList("""
                select articulo_id, precio_anterior, precio_nuevo, usuario_id from historial_precios
                where lista_id = ? order by id
                """, lista.getId());
        assertThat(historial).hasSize(2).allSatisfy(h -> assertThat(h.get("usuario_id")).isEqualTo(dueno.getId()));
        assertThat(historial.get(1)).containsEntry("articulo_id", maceta.getId());
        assertThat((BigDecimal) historial.get(1).get("precio_anterior")).isEqualByComparingTo("1800");

        List<EventoAuditoria> registros = eventos.stream(EventoAuditoria.class)
                .filter(e -> e.accion().equals("importar_precios")).toList();
        assertThat(registros).hasSize(1);
        assertThat(registros.get(0).ambitosAfectados()).containsExactly(centro.getId());
        assertThat(registros.get(0).entidadId()).isEqualTo(lista.getId());
        assertThat(new java.util.HashMap<String, Object>(registros.get(0).detalle())).containsEntry("archivo", "precios.csv")
                .containsEntry("creadas", 1).containsEntry("actualizadas", 1);
    }

    @Test
    void unArticuloDeOtroAmbitoEsElMismoErrorQueUnoQueNoExiste() throws Exception {
        vistaPrevia("articulo,precio\nRegadera,100\nNo existe,100\nPerlita,100\n")
                .andExpect(jsonPath("$.errores").value(3))
                .andExpect(jsonPath("$.detalle[0].motivo").value("No existe el artículo 'Regadera'."))
                .andExpect(jsonPath("$.detalle[1].motivo").value("No existe el artículo 'No existe'."))
                // los insumos de la fábrica el local ni los ve
                .andExpect(jsonPath("$.detalle[2].motivo").value("No existe el artículo 'Perlita'."));

        importar(lista.getId(), "articulo,precio\nMaceta,999\nRegadera,100\n")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detalle[1].fila").value(3));
        em.flush();
        assertThat(precio(maceta)).isEqualByComparingTo("1800");
        assertThat(jdbc.queryForObject("select count(*) from historial_precios where lista_id = ?", Integer.class,
                lista.getId())).isZero();
    }

    @Test
    void losPreciosAceptanComaOPuntoPeroNoSeparadorDeMiles() throws Exception {
        vistaPrevia("articulo,precio\nCoco,\"1500,5\"\nMaceta,1.234,50\n")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.detalle[0].precioNuevo").value(1500.50))
                .andExpect(jsonPath("$.detalle[1].accion").value("error"));
        vistaPrevia("articulo;precio\nCoco;1.234,50\nMaceta;1.000\n")
                .andExpect(jsonPath("$.detalle[0].motivo").value(
                        "El precio '1.234,50' no es válido: usá coma o punto para los decimales, sin separador de miles."))
                .andExpect(jsonPath("$.detalle[1].motivo").value("El precio '1.000' tiene más de dos decimales: los "
                        + "decimales van con coma o punto, sin separador de miles."));
        vistaPrevia("articulo;precio\nCoco;-5\nMaceta;abc\n")
                .andExpect(jsonPath("$.detalle[0].motivo").value("El precio no puede ser negativo."))
                .andExpect(jsonPath("$.detalle[1].motivo").value("El precio 'abc' no es un número."));
    }

    @Test
    void laListaTieneQueSerDelPropioAmbitoYEstarActiva() throws Exception {
        ListaPrecios deNorte = datos.lista(norte, "Público", true, true);
        ListaPrecios vieja = datos.lista(centro, "Vieja", true, false);
        vieja.setActivo(false);
        em.flush();

        importar(deNorte.getId(), "articulo,precio\nCoco,1\n").andExpect(status().isNotFound());
        importar(vieja.getId(), "articulo,precio\nCoco,1\n").andExpect(status().isConflict());
    }

    @Test
    void laVistaPreviaNoCambiaNingunPrecio() throws Exception {
        Map<String, Object> antes = foto();

        vistaPrevia("articulo,precio\nCoco,1500\nMaceta,2000\n").andExpect(status().isOk());

        em.flush();
        assertThat(foto()).isEqualTo(antes);
    }

    @Test
    void elEmpleadoYElAdministradorReciben403YSinSesion401() throws Exception {
        for (RequestPostProcessor sesion : List.of(datos.en(centro, Perfil.empleado_local), datos.admin())) {
            mvc.perform(multipart("/api/importacion/precios/vista-previa").file(archivo("articulo,precio\nCoco,1\n"))
                    .param("listaId", lista.getId().toString()).with(sesion)).andExpect(status().isForbidden());
            mvc.perform(multipart("/api/importacion/precios").file(archivo("articulo,precio\nCoco,1\n"))
                    .param("listaId", lista.getId().toString()).with(sesion)).andExpect(status().isForbidden());
        }
        mvc.perform(multipart("/api/importacion/precios").file(archivo("articulo,precio\nCoco,1\n"))
                .param("listaId", lista.getId().toString()).with(csrf())).andExpect(status().isUnauthorized());
    }

    private Map<String, Object> foto() {
        return jdbc.queryForMap("""
                select (select string_agg(articulo_id || ':' || precio || ':' || updated_at, '|' order by id)
                        from lista_precios_items) as precios,
                       (select count(*) from historial_precios) as historial,
                       (select count(*) from auditorias) as auditorias
                """);
    }

    private BigDecimal precio(Articulo articulo) {
        return jdbc.queryForObject("select precio from lista_precios_items where lista_id = ? and articulo_id = ?",
                BigDecimal.class, lista.getId(), articulo.getId());
    }

    private ResultActions vistaPrevia(String csv) throws Exception {
        return mvc.perform(multipart("/api/importacion/precios/vista-previa").file(archivo(csv))
                .param("listaId", lista.getId().toString()).with(duenoCentro));
    }

    private ResultActions importar(Long listaId, String csv) throws Exception {
        return mvc.perform(multipart("/api/importacion/precios").file(archivo(csv))
                .param("listaId", listaId.toString()).with(duenoCentro));
    }

    private static MockMultipartFile archivo(String csv) {
        return new MockMultipartFile("archivo", "precios.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
    }
}
