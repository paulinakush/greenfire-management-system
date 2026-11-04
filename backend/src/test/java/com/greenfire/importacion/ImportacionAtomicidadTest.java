package com.greenfire.importacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.greenfire.Datos;
import com.greenfire.DatosConfirmados;
import com.greenfire.PruebaIntegracion;
import com.greenfire.ambitos.Ambito;
import com.greenfire.catalogo.Articulo;
import com.greenfire.catalogo.TipoArticulo;
import com.greenfire.precios.ListaPrecios;
import com.greenfire.seguridad.Perfil;

/**
 * La confirmación de una importación con transacciones reales (NOT_SUPPORTED): si algo falla después de empezar a
 * guardar, se revierte todo. Lo que dejan guardado lo borra {@link DatosConfirmados}.
 */
@PruebaIntegracion
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ImportacionAtomicidadTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    Datos datos;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TransactionTemplate transaccion;

    DatosConfirmados confirmados;
    Ambito fabrica;
    RequestPostProcessor dueno;

    @BeforeEach
    void datos() {
        confirmados = new DatosConfirmados(jdbc, transaccion);
        fabrica = datos.fabrica();
        dueno = datos.en(fabrica, Perfil.dueno_fabrica);
    }

    @AfterEach
    void limpiar() {
        confirmados.limpiar();
    }

    @Test
    void siFallaDespuesDeGuardarLosArticulosNoQuedaNinguno() throws Exception {
        byte[] csv = "nombre,tipo,unidad,alicuota_iva\nPerlita,insumo,kg,21\nTurba,insumo,kg,21\nArena,insumo,kg,21\n"
                .getBytes(StandardCharsets.UTF_8);

        // falla el último paso, la auditoría: para entonces los artículos ya se insertaron
        int estado = conFalla("importar_articulos", () -> importar("/api/importacion/articulos", csv, null));

        assertThat(estado).isEqualTo(500);
        assertThat(nuevos("articulos")).isZero();
        // sin la falla, el mismo archivo se aplica
        assertThat(importar("/api/importacion/articulos", csv, null)).isEqualTo(200);
        assertThat(nuevos("articulos")).isEqualTo(3);
    }

    @Test
    void siFallaDespuesDeGuardarLosPreciosNoQuedaNingunoNiSuHistorial() throws Exception {
        Articulo coco = datos.articulo(fabrica, TipoArticulo.producto_terminado, "Coco", datos.categoria("Sustratos"));
        Articulo turba = datos.articulo(fabrica, TipoArticulo.producto_terminado, "Turba", datos.categoria("Turbas"));
        ListaPrecios lista = datos.lista(fabrica, "Para locales", false, true);
        datos.precio(lista, coco, "1000");
        byte[] csv = "articulo;precio\nCoco;1200\nTurba;800\n".getBytes(StandardCharsets.UTF_8);

        int estado = conFalla("importar_precios", () -> importar("/api/importacion/precios", csv, lista.getId()));

        assertThat(estado).isEqualTo(500);
        assertThat(jdbc.queryForObject("select precio from lista_precios_items where lista_id = ? and articulo_id = ?",
                BigDecimal.class, lista.getId(), coco.getId())).isEqualByComparingTo("1000");
        assertThat(jdbc.queryForObject("select count(*) from lista_precios_items where lista_id = ? and articulo_id = ?",
                Integer.class, lista.getId(), turba.getId())).isZero();
        assertThat(nuevos("historial_precios")).isZero();

        assertThat(importar("/api/importacion/precios", csv, lista.getId())).isEqualTo(200);
        assertThat(nuevos("historial_precios")).isEqualTo(2);
    }

    private interface Importacion {
        int ejecutar() throws Exception;
    }

    private int conFalla(String accion, Importacion importacion) throws Exception {
        jdbc.execute("alter table auditorias add constraint prueba_falla check (accion <> '" + accion + "') not valid");
        try {
            return importacion.ejecutar();
        } finally {
            jdbc.execute("alter table auditorias drop constraint prueba_falla");
        }
    }

    private int importar(String url, byte[] csv, Long listaId) throws Exception {
        var pedido = multipart(url).file(new MockMultipartFile("archivo", "archivo.csv", "text/csv", csv)).with(dueno);
        if (listaId != null) {
            pedido.param("listaId", listaId.toString());
        }
        return mvc.perform(pedido).andReturn().getResponse().getStatus();
    }

    private int nuevos(String tabla) {
        return jdbc.queryForObject("select count(*) from " + tabla + " where id > ?", Integer.class,
                confirmados.ultimoId(tabla));
    }
}
