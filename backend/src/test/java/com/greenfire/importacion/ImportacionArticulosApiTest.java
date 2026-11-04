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
import com.greenfire.seguridad.Perfil;

import jakarta.persistence.EntityManager;

/** Importación CSV de artículos: vista previa, confirmación todo o nada, formatos, límites y permisos. */
@PruebaIntegracion
class ImportacionArticulosApiTest {

    private static final String ENCABEZADO = "nombre,tipo,categoria,unidad,alicuota_iva,marca,visible_web\n";

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
    Categoria sustratos;
    Articulo coco;
    RequestPostProcessor duenoFabrica;
    RequestPostProcessor duenoCentro;

    @BeforeEach
    void datos() {
        fabrica = datos.fabrica();
        centro = datos.local("Centro");
        sustratos = datos.categoria("Sustratos");
        coco = datos.articulo(fabrica, TipoArticulo.producto_terminado, "Coco", sustratos);
        duenoFabrica = datos.en(fabrica, Perfil.dueno_fabrica);
        duenoCentro = datos.en(centro, Perfil.dueno_local);
        em.flush();
    }

    // --- Archivo válido ---

    @Test
    void unArchivoValidoCreaYActualizaArticulos() throws Exception {
        String csv = ENCABEZADO
                + "COCO,producto_terminado,%s,unidad,21,Green Fire,sí\n".formatted(sustratos.getNombre())
                + "Turba,producto terminado,%s,dm3,10.5,,no\n".formatted(sustratos.getNombre().toUpperCase())
                + "Perlita,insumo,,kg,21,,\n";

        vistaPrevia(duenoFabrica, csv)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aplicado").value(false))
                .andExpect(jsonPath("$.filas").value(3))
                .andExpect(jsonPath("$.crear").value(2))
                .andExpect(jsonPath("$.actualizar").value(1))
                .andExpect(jsonPath("$.errores").value(0))
                .andExpect(jsonPath("$.detalle[0].fila").value(2))
                .andExpect(jsonPath("$.detalle[0].accion").value("actualizar"))
                .andExpect(jsonPath("$.detalle[0].campos").value(org.hamcrest.Matchers.contains("nombre", "marca",
                        "visible_web")))
                .andExpect(jsonPath("$.detalle[1].accion").value("crear"))
                .andExpect(jsonPath("$.detalle[2].accion").value("crear"));

        importar(duenoFabrica, csv)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aplicado").value(true))
                .andExpect(jsonPath("$.archivo").value("articulos.csv"));

        em.flush();
        Map<String, Object> actualizado = jdbc.queryForMap(
                "select nombre, marca, visible_web from articulos where id = ?", coco.getId());
        assertThat(actualizado).containsEntry("nombre", "COCO").containsEntry("marca", "Green Fire")
                .containsEntry("visible_web", true);
        assertThat(jdbc.queryForMap("""
                select tipo, unidad_medida, alicuota_iva, categoria_id from articulos
                where ambito_id = ? and nombre = 'Turba'
                """, fabrica.getId()))
                .containsEntry("tipo", "producto_terminado").containsEntry("unidad_medida", "dm3")
                .containsEntry("categoria_id", sustratos.getId());
        assertThat(jdbc.queryForObject("select categoria_id from articulos where ambito_id = ? and nombre = 'Perlita'",
                Long.class, fabrica.getId())).isNull();

        List<EventoAuditoria> registros = eventos.stream(EventoAuditoria.class)
                .filter(e -> e.accion().equals("importar_articulos")).toList();
        assertThat(registros).hasSize(1);
        assertThat(registros.get(0).ambitosAfectados()).containsExactly(fabrica.getId());
        assertThat(new java.util.HashMap<String, Object>(registros.get(0).detalle())).containsEntry("archivo", "articulos.csv")
                .containsEntry("creadas", 2).containsEntry("actualizadas", 1);
    }

    @Test
    void elLocalImportaSuReventa() throws Exception {
        importar(duenoCentro, ENCABEZADO + "Maceta 20 cm,reventa,%s,unidad,21,,sí\n".formatted(sustratos.getNombre()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.crear").value(1));
        em.flush();
        assertThat(jdbc.queryForObject("select tipo from articulos where ambito_id = ? and nombre = 'Maceta 20 cm'",
                String.class, centro.getId())).isEqualTo("reventa");
    }

    // --- Errores ---

    @Test
    void unaFilaConErrorNoAplicaNinguna() throws Exception {
        String csv = ENCABEZADO
                + "Turba,producto_terminado,%s,dm3,21,,\n".formatted(sustratos.getNombre())
                + "Corteza,producto_terminado,No existe,kg,21,,\n"
                + "Perlita,insumo,,bolsa,21,,\n";

        vistaPrevia(duenoFabrica, csv)
                .andExpect(jsonPath("$.errores").value(2))
                .andExpect(jsonPath("$.crear").value(1))
                .andExpect(jsonPath("$.detalle[1].fila").value(3))
                .andExpect(jsonPath("$.detalle[1].accion").value("error"))
                .andExpect(jsonPath("$.detalle[1].motivo").value("No existe la categoría 'No existe'."))
                .andExpect(jsonPath("$.detalle[2].fila").value(4))
                .andExpect(jsonPath("$.detalle[2].motivo").value(
                        "La unidad 'bolsa' no existe: es unidad, kg, litro, m3 o dm3."));

        importar(duenoFabrica, csv)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.aplicado").value(false))
                .andExpect(jsonPath("$.errores").value(2));

        assertThat(jdbc.queryForObject("select count(*) from articulos where ambito_id = ?", Integer.class,
                fabrica.getId())).isOne();
        assertThat(eventos.stream(EventoAuditoria.class).filter(e -> e.accion().equals("importar_articulos")))
                .isEmpty();
    }

    @Test
    void cadaDuenoImportaSoloLoQueAdministra() throws Exception {
        vistaPrevia(duenoCentro, ENCABEZADO + "Coco Plus,producto_terminado,%s,unidad,21,,\n"
                .formatted(sustratos.getNombre()))
                .andExpect(jsonPath("$.detalle[0].accion").value("error"))
                .andExpect(jsonPath("$.detalle[0].motivo").value("El local solo importa reventa: los insumos y los "
                        + "productos terminados los administra la fábrica."));
        vistaPrevia(duenoFabrica, ENCABEZADO + "Maceta,reventa,%s,unidad,21,,\n".formatted(sustratos.getNombre()))
                .andExpect(jsonPath("$.detalle[0].motivo").value("La reventa la importa cada local."));
        // el Coco de la fábrica no es del local: con el mismo nombre, el local crea su propia reventa
        vistaPrevia(duenoCentro, ENCABEZADO + "Coco,reventa,%s,unidad,21,,\n".formatted(sustratos.getNombre()))
                .andExpect(jsonPath("$.detalle[0].accion").value("crear"));
    }

    @Test
    void laCategoriaTieneQueExistirYEstarActivaYElTipoNoCambia() throws Exception {
        Categoria vieja = datos.categoria("Vieja");
        vieja.setActivo(false);
        em.flush();

        vistaPrevia(duenoFabrica, ENCABEZADO
                + "Turba,producto_terminado,%s,dm3,21,,\n".formatted(vieja.getNombre())
                + "Turba 2,producto_terminado,,dm3,21,,\n"
                + "Coco,insumo,,kg,21,,\n"
                + "Perlita,insumo,%s,kg,21,,\n".formatted(sustratos.getNombre())
                + "Arena,insumo,,kg,22,,\n"
                + "turba 2,producto_terminado,%s,dm3,21,,\n".formatted(sustratos.getNombre()))
                .andExpect(jsonPath("$.errores").value(6))
                .andExpect(jsonPath("$.detalle[0].motivo").value("La categoría '" + vieja.getNombre()
                        + "' está desactivada."))
                .andExpect(jsonPath("$.detalle[1].motivo").value(
                        "Los productos terminados y la reventa llevan categoría."))
                .andExpect(jsonPath("$.detalle[2].motivo").value(
                        "'Coco' ya existe como producto_terminado: el tipo no se cambia."))
                .andExpect(jsonPath("$.detalle[3].motivo").value("Los insumos no llevan categoría."))
                .andExpect(jsonPath("$.detalle[4].motivo").value("La alícuota de IVA debe ser 0, 10.5, 21 o 27."))
                .andExpect(jsonPath("$.detalle[5].motivo").value("El artículo 'turba 2' ya está en la fila 3."));
    }

    // --- Formatos ---

    @Test
    void aceptaComaOPuntoYComaComoSeparadorYDecimalesConComaOPunto() throws Exception {
        String categoria = sustratos.getNombre();
        importar(duenoFabrica, "nombre;tipo;categoria;unidad;alicuota_iva\nA;producto_terminado;%s;kg;10,5\n"
                .formatted(categoria)).andExpect(status().isOk());
        importar(duenoFabrica, "nombre,tipo,categoria,unidad,alicuota_iva\nB,producto_terminado,%s,kg,10.5\n"
                .formatted(categoria)).andExpect(status().isOk());
        importar(duenoFabrica, "nombre,tipo,categoria,unidad,alicuota_iva\r\nC,producto_terminado,%s,kg,\"10,5\"\r\n"
                .formatted(categoria)).andExpect(status().isOk());
        // con BOM, encabezado con tildes y mayúsculas, y un valor entre comillas con el separador adentro
        importar(duenoFabrica, "﻿Nombre;Tipo;Categoría;Unidad;Alícuota IVA\n\"D; especial\";producto_terminado;%s;kg;10.5\n"
                .formatted(categoria)).andExpect(status().isOk());

        em.flush();
        assertThat(jdbc.queryForList("""
                select alicuota_iva from articulos where ambito_id = ? and nombre in ('A', 'B', 'C', 'D; especial')
                """, BigDecimal.class, fabrica.getId())).hasSize(4)
                .allSatisfy(a -> assertThat(a).isEqualByComparingTo("10.5"));
    }

    @Test
    void elArchivoTieneQueEstarEnUtf8YConLasColumnasQueCorresponden() throws Exception {
        byte[] latin1 = (ENCABEZADO + "Ñandú,producto_terminado,%s,kg,21,,\n".formatted(sustratos.getNombre()))
                .getBytes(StandardCharsets.ISO_8859_1);
        mvc.perform(multipart("/api/importacion/articulos/vista-previa").file(archivo(latin1)).with(duenoFabrica))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value(
                        "El archivo tiene que estar en UTF-8. Desde la planilla, guardalo como \"CSV UTF-8\"."));
        vistaPrevia(duenoFabrica, "nombre,tipo,unidad\nA,insumo,kg\n")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value("Falta la columna 'alicuota_iva'."));
        vistaPrevia(duenoFabrica, "nombre,tipo,unidad,alicuota_iva,costo\nA,insumo,kg,21,100\n")
                .andExpect(status().isBadRequest());
        vistaPrevia(duenoFabrica, ENCABEZADO).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value("El archivo no tiene filas para importar."));
    }

    @Test
    void unArchivoDemasiadoGrandeOConDemasiadasFilasSeRechazaConUnMensajeClaro() throws Exception {
        byte[] grande = (ENCABEZADO + "x".repeat(LectorCsv.TAMANO_MAXIMO)).getBytes(StandardCharsets.UTF_8);
        mvc.perform(multipart("/api/importacion/articulos/vista-previa").file(archivo(grande)).with(duenoFabrica))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.mensaje").value("El archivo supera el máximo de 1024 KB."));

        StringBuilder muchas = new StringBuilder(ENCABEZADO);
        for (int i = 0; i <= LectorCsv.FILAS_MAXIMAS; i++) {
            muchas.append("I").append(i).append(",insumo,,kg,21,,\n");
        }
        vistaPrevia(duenoFabrica, muchas.toString())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value("El archivo supera el máximo de 2000 filas."));
    }

    @Test
    void unTextoQueEmpiezaComoFormulaSeGuardaTalCual() throws Exception {
        importar(duenoFabrica, ENCABEZADO + "\"=HIPERVINCULO(\"\"x\"\")\",insumo,,kg,21,+54 11,\n"
                + "@Sustrato,insumo,,kg,21,-especial,\n")
                .andExpect(status().isOk());
        em.flush();
        assertThat(jdbc.queryForList("select nombre || ' ' || marca from articulos where ambito_id = ? and tipo = 'insumo' order by id",
                String.class, fabrica.getId())).containsExactly("=HIPERVINCULO(\"x\") +54 11", "@Sustrato -especial");
    }

    // --- Vista previa y permisos ---

    @Test
    void laVistaPreviaNoCambiaNadaEnLaBase() throws Exception {
        String csv = ENCABEZADO + "Coco,producto_terminado,%s,kg,27,Otra,sí\nTurba,producto_terminado,%s,dm3,21,,\n"
                .formatted(sustratos.getNombre(), sustratos.getNombre());
        Map<String, Object> antes = foto();

        vistaPrevia(duenoFabrica, csv).andExpect(status().isOk())
                .andExpect(jsonPath("$.actualizar").value(1)).andExpect(jsonPath("$.crear").value(1));

        em.flush();
        assertThat(foto()).isEqualTo(antes);
        assertThat(eventos.stream(EventoAuditoria.class)).isEmpty();
    }

    @Test
    void elEmpleadoYElAdministradorReciben403YSinSesion401() throws Exception {
        String csv = ENCABEZADO + "A,reventa,%s,kg,21,,\n".formatted(sustratos.getNombre());
        for (RequestPostProcessor sesion : List.of(datos.en(centro, Perfil.empleado_local), datos.admin())) {
            vistaPrevia(sesion, csv).andExpect(status().isForbidden());
            importar(sesion, csv).andExpect(status().isForbidden());
        }
        mvc.perform(multipart("/api/importacion/articulos").file(archivo(csv.getBytes(StandardCharsets.UTF_8)))
                .with(csrf())).andExpect(status().isUnauthorized());
    }

    // --- Ayudas ---

    private Map<String, Object> foto() {
        return jdbc.queryForMap("""
                select (select count(*) from articulos) as articulos,
                       (select string_agg(id || nombre || coalesce(marca, '') || alicuota_iva || visible_web
                                          || unidad_medida || updated_at, '|' order by id) from articulos) as datos,
                       (select count(*) from auditorias) as auditorias
                """);
    }

    private ResultActions vistaPrevia(RequestPostProcessor sesion, String csv) throws Exception {
        return mvc.perform(multipart("/api/importacion/articulos/vista-previa")
                .file(archivo(csv.getBytes(StandardCharsets.UTF_8))).with(sesion));
    }

    private ResultActions importar(RequestPostProcessor sesion, String csv) throws Exception {
        return mvc.perform(multipart("/api/importacion/articulos")
                .file(archivo(csv.getBytes(StandardCharsets.UTF_8))).with(sesion));
    }

    private static MockMultipartFile archivo(byte[] contenido) {
        return new MockMultipartFile("archivo", "articulos.csv", "text/csv", contenido);
    }
}
