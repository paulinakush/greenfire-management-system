package com.greenfire.catalogo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.greenfire.Datos;
import com.greenfire.PruebaIntegracion;
import com.greenfire.ambitos.Ambito;
import com.greenfire.ambitos.AmbitoRepository;
import com.greenfire.ambitos.TipoAmbito;
import com.greenfire.fiscal.CondicionIva;
import com.greenfire.precios.ListaPrecios;
import com.greenfire.stock.UbicacionStock;

/**
 * Catálogo del sitio público: sin login, de solo lectura y sin nada interno. Los tests piden sin sesión, como un
 * visitante de la web.
 */
@PruebaIntegracion
class CatalogoPublicoApiTest {

    private static final String CACHE = "max-age=60, public";
    private static final String ARTICULO_NO_EXISTE = "No existe el artículo indicado.";
    private static final String LOCAL_NO_EXISTE = "No existe el local indicado.";

    @Autowired
    MockMvc mvc;
    @Autowired
    Datos datos;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    AmbitoRepository ambitos;

    final ObjectMapper mapper = new ObjectMapper();

    Ambito fabrica;
    Ambito palermo;
    Ambito belgrano;
    Categoria sustratos;
    Categoria macetas;
    Articulo coco;
    Articulo maceta;
    ListaPrecios listaPalermo;
    ListaPrecios listaBelgrano;
    ListaPrecios listaFabrica;

    @BeforeEach
    void datos() {
        fabrica = datos.fabrica();
        palermo = visible(datos.ambito(TipoAmbito.local, "Palermo", CondicionIva.responsable_inscripto));
        belgrano = visible(datos.ambito(TipoAmbito.local, "Belgrano", CondicionIva.monotributo));
        sustratos = datos.categoria("Sustratos");
        macetas = datos.categoria("Macetas");

        coco = publicable(datos.articulo(fabrica, TipoArticulo.producto_terminado, "Coco Elite", sustratos));
        coco.setMarca("Green Fire");
        coco.setDescripcion("Sustrato de fibra de coco");
        coco.setImagen("assets/productos/coco-elite.webp");
        maceta = publicable(datos.articulo(palermo, TipoArticulo.reventa, "Maceta soplada", macetas));
        maceta.setAlicuotaIva(new BigDecimal("10.5"));

        // Palermo es responsable inscripto con lista sin IVA; Belgrano, monotributista (su lista es con IVA).
        listaPalermo = datos.lista(palermo, "Público", false, true);
        datos.precio(listaPalermo, coco, "1000");
        datos.precio(listaPalermo, maceta, "2000");
        listaBelgrano = datos.lista(belgrano, "Público", true, true);
        datos.precio(listaBelgrano, coco, "1500");
        listaFabrica = datos.lista(fabrica, "Para locales", false, true);
        datos.precio(listaFabrica, coco, "7777.77");
    }

    // --- Claves exactas de cada endpoint ---

    @Test
    void lasCategoriasTienenSoloLasClavesPermitidas() throws Exception {
        JsonNode categorias = json("/api/publico/categorias");

        assertThat(categorias).isNotEmpty();
        categorias.forEach(c -> assertThat(claves(c)).containsExactlyInAnyOrder("id", "nombre", "descripcion"));
    }

    @Test
    void elCatalogoTieneSoloLasClavesPermitidas() throws Exception {
        JsonNode pagina = json("/api/publico/catalogo?localId=" + palermo.getId());

        assertThat(claves(pagina)).containsExactlyInAnyOrder("localId", "localNombre", "articulos", "pagina",
                "tamano", "total");
        assertThat(pagina.get("articulos")).hasSize(2);
        pagina.get("articulos").forEach(a -> assertThat(claves(a)).containsExactlyInAnyOrder(
                "id", "nombre", "descripcion", "categoriaId", "categoriaNombre", "marca", "unidadMedida", "imagen",
                "precio", "disponible"));
    }

    @Test
    void elDetalleTieneSoloLasClavesPermitidas() throws Exception {
        JsonNode articulo = json("/api/publico/catalogo/" + coco.getId() + "?localId=" + palermo.getId());

        assertThat(claves(articulo)).containsExactlyInAnyOrder("id", "nombre", "descripcion", "categoriaId",
                "categoriaNombre", "marca", "unidadMedida", "imagen", "precio", "disponible");
        assertThat(articulo.get("nombre").asText()).isEqualTo("Coco Elite");
        assertThat(articulo.get("marca").asText()).isEqualTo("Green Fire");
        assertThat(articulo.get("imagen").asText()).isEqualTo("assets/productos/coco-elite.webp");
    }

    @Test
    void losLocalesTienenSoloLasClavesPermitidas() throws Exception {
        JsonNode locales = json("/api/publico/locales");

        assertThat(locales).isNotEmpty();
        locales.forEach(l -> assertThat(claves(l)).containsExactlyInAnyOrder("id", "nombre", "direccion",
                "horarios", "telefono", "email", "esCentral"));
    }

    // --- Nada interno en ninguna respuesta ---

    @Test
    void ningunaRespuestaExponeDatosInternos() throws Exception {
        UbicacionStock deposito = datos.ubicacion(palermo, "Depósito Secreto");
        datos.stock(coco, deposito, "54321");
        jdbc.update("update stock_articulos set stock_minimo = 12345, stock_maximo = 98765 where articulo_id = ?",
                coco.getId());
        datos.costo(coco, fabrica, "4444.44");
        datos.costo(maceta, palermo, "3333.33");
        jdbc.update("""
                insert into historial_precios (lista_id, articulo_id, precio_anterior, precio_nuevo, usuario_id)
                values (?, ?, 6666.66, 1000, ?)
                """, listaPalermo.getId(), coco.getId(), datos.usuario("Precios").getId());
        datos.lista(palermo, "Mayorista secreta", false, false);
        String cuitFabrica = fabrica.getEntidadFiscal().getCuit();
        String cuitPalermo = palermo.getEntidadFiscal().getCuit();
        String cuitBelgrano = belgrano.getEntidadFiscal().getCuit();

        List<String> respuestas = new ArrayList<>();
        for (String url : List.of(
                "/api/publico/categorias",
                "/api/publico/locales",
                "/api/publico/catalogo?localId=" + palermo.getId(),
                "/api/publico/catalogo?localId=" + belgrano.getId(),
                "/api/publico/catalogo/" + coco.getId() + "?localId=" + palermo.getId(),
                "/api/publico/catalogo/" + coco.getId() + "?localId=" + belgrano.getId(),
                "/api/publico/catalogo/" + maceta.getId() + "?localId=" + palermo.getId())) {
            respuestas.add(texto(url));
        }

        for (String respuesta : respuestas) {
            // ninguna clave, en ningún nivel, habla de costos, stock, listas, historial ni datos fiscales
            assertThat(todasLasClaves(mapper.readTree(respuesta)))
                    .noneMatch(k -> k.toLowerCase().matches(".*(costo|margen|neto|cantidad|stock|minimo|maximo"
                            + "|ubicacion|lista|historial|lote|movimiento|cuit|iva|fiscal|razon|condicion).*"));
            // y ningún valor interno aparece en el texto
            assertThat(respuesta).doesNotContain(
                    "4444.44", "3333.33", // costos de la fábrica y del local
                    "54321", "12345", "98765", // cantidad, mínimo y máximo
                    "Depósito Secreto", "Principal", // ubicaciones
                    "7777.77", "Para locales", // la lista de la fábrica para locales
                    "6666.66", "Mayorista secreta", // historial y otras listas
                    cuitFabrica, cuitPalermo, cuitBelgrano, "responsable_inscripto", "monotributo", "Entidad ");
        }
    }

    // --- Qué artículos aparecen ---

    @Test
    void soloAparecenArticulosActivosVisiblesYConPrecioEnLaListaPredeterminada() throws Exception {
        Articulo inactivo = publicable(datos.articulo(fabrica, TipoArticulo.producto_terminado, "Inactivo", sustratos));
        Articulo oculto = datos.articulo(fabrica, TipoArticulo.producto_terminado, "Oculto", sustratos);
        Categoria vieja = datos.categoria("Vieja");
        Articulo deCategoriaInactiva = publicable(datos.articulo(fabrica, TipoArticulo.producto_terminado,
                "De categoría inactiva", vieja));
        Articulo sinPrecio = publicable(datos.articulo(fabrica, TipoArticulo.producto_terminado, "Sin precio",
                sustratos));
        Articulo soloMayorista = publicable(datos.articulo(fabrica, TipoArticulo.producto_terminado,
                "Solo mayorista", sustratos));
        datos.precio(datos.lista(palermo, "Mayorista", false, false), soloMayorista, "900");
        for (Articulo a : List.of(inactivo, oculto, deCategoriaInactiva)) {
            datos.precio(listaPalermo, a, "100");
        }
        inactivo.setActivo(false);
        vieja.setActivo(false);

        mvc.perform(get("/api/publico/catalogo").param("localId", palermo.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.articulos[*].nombre").value(contains("Coco Elite", "Maceta soplada")))
                .andExpect(jsonPath("$.total").value(2));
        mvc.perform(get("/api/publico/catalogo").param("localId", belgrano.getId().toString()))
                .andExpect(jsonPath("$.articulos[*].nombre").value(contains("Coco Elite")));

        for (Articulo a : List.of(inactivo, oculto, deCategoriaInactiva, sinPrecio, soloMayorista)) {
            mvc.perform(get("/api/publico/catalogo/" + a.getId()).param("localId", palermo.getId().toString()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.mensaje").value(ARTICULO_NO_EXISTE));
        }
    }

    @Test
    void unArticuloQueNoSeVendeEnElLocalOEstaOcultoRespondeIgualQueUnoQueNoExiste() throws Exception {
        String inexistente = mvc.perform(get("/api/publico/catalogo/999999999")
                        .param("localId", palermo.getId().toString()))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        // la maceta es reventa de Palermo: en Belgrano no se vende
        String noSeVende = mvc.perform(get("/api/publico/catalogo/" + maceta.getId())
                        .param("localId", belgrano.getId().toString()))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        coco.setVisibleWeb(false);
        String oculto = mvc.perform(get("/api/publico/catalogo/" + coco.getId())
                        .param("localId", palermo.getId().toString()))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        assertThat(noSeVende).isEqualTo(inexistente);
        assertThat(oculto).isEqualTo(inexistente);
        assertThat(mapper.readTree(inexistente).get("mensaje").asText()).isEqualTo(ARTICULO_NO_EXISTE);
    }

    @Test
    void unLocalQueNoSeMuestraEnLaWebRespondeIgualQueUnoQueNoExiste() throws Exception {
        Ambito oculto = datos.local("Oculto");
        datos.precio(datos.lista(oculto, "Público", true, true), coco, "1000");
        Ambito cerrado = visible(datos.local("Cerrado"));
        datos.precio(datos.lista(cerrado, "Público", true, true), coco, "1000");
        cerrado.setActivo(false);

        for (Long id : List.of(oculto.getId(), cerrado.getId(), fabrica.getId(), 999_999_999L)) {
            mvc.perform(get("/api/publico/catalogo").param("localId", id.toString()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.mensaje").value(LOCAL_NO_EXISTE));
            mvc.perform(get("/api/publico/catalogo/" + coco.getId()).param("localId", id.toString()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.mensaje").value(LOCAL_NO_EXISTE));
        }
    }

    // --- Precio al público ---

    @Test
    void enUnLocalResponsableInscriptoConListaSinIvaElPrecioPublicoSumaElIva() throws Exception {
        mvc.perform(get("/api/publico/catalogo/" + coco.getId()).param("localId", palermo.getId().toString()))
                .andExpect(jsonPath("$.precio").value(1210.00)); // 1000 más 21 %
        mvc.perform(get("/api/publico/catalogo/" + maceta.getId()).param("localId", palermo.getId().toString()))
                .andExpect(jsonPath("$.precio").value(2210.00)); // 2000 más 10,5 %
        mvc.perform(get("/api/publico/catalogo").param("localId", palermo.getId().toString()))
                .andExpect(jsonPath("$.articulos[0].precio").value(1210.00))
                .andExpect(jsonPath("$.articulos[1].precio").value(2210.00));
    }

    @Test
    void enUnLocalResponsableInscriptoConListaConIvaElPrecioPublicoEsElDeLaLista() throws Exception {
        Ambito cordoba = visible(datos.ambito(TipoAmbito.local, "Córdoba", CondicionIva.responsable_inscripto));
        datos.precio(datos.lista(cordoba, "Público", true, true), coco, "1210");

        mvc.perform(get("/api/publico/catalogo/" + coco.getId()).param("localId", cordoba.getId().toString()))
                .andExpect(jsonPath("$.precio").value(1210.00));
    }

    @Test
    void enUnLocalQueNoDiscriminaIvaElPrecioDeLaListaEsElPrecioFinal() throws Exception {
        mvc.perform(get("/api/publico/catalogo/" + coco.getId()).param("localId", belgrano.getId().toString()))
                .andExpect(jsonPath("$.precio").value(1500.00));
        mvc.perform(get("/api/publico/catalogo").param("localId", belgrano.getId().toString()))
                .andExpect(jsonPath("$.articulos[0].precio").value(1500.00));
    }

    // --- Disponibilidad ---

    @Test
    void laDisponibilidadEsSoloSiONoYSumaLasUbicacionesActivasDelLocal() throws Exception {
        UbicacionStock principal = datos.principal(palermo);
        UbicacionStock deposito = datos.ubicacion(palermo, "Depósito");
        UbicacionStock cerrada = datos.ubicacion(palermo, "Cerrada");
        datos.stock(coco, principal, "2");
        datos.stock(coco, principal, "-2"); // en Principal quedó en cero
        datos.stock(coco, deposito, "3");
        datos.stock(maceta, cerrada, "5");
        cerrada.setActivo(false);

        mvc.perform(get("/api/publico/catalogo").param("localId", palermo.getId().toString()))
                .andExpect(jsonPath("$.articulos[0].nombre").value("Coco Elite"))
                .andExpect(jsonPath("$.articulos[0].disponible").value(true))
                .andExpect(jsonPath("$.articulos[1].nombre").value("Maceta soplada"))
                .andExpect(jsonPath("$.articulos[1].disponible").value(false)); // solo hay en una ubicación inactiva
        // el stock de Palermo no cuenta para Belgrano
        mvc.perform(get("/api/publico/catalogo/" + coco.getId()).param("localId", belgrano.getId().toString()))
                .andExpect(jsonPath("$.disponible").value(false));
        mvc.perform(get("/api/publico/catalogo/" + coco.getId()).param("localId", palermo.getId().toString()))
                .andExpect(jsonPath("$.disponible").value(true));
    }

    // --- Local central por defecto ---

    @Test
    void sinElegirLocalSeUsaElCentral() throws Exception {
        ambitos.flush();
        jdbc.update("update ambitos set es_central = true where id = ?", belgrano.getId());

        mvc.perform(get("/api/publico/catalogo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.localId").value(belgrano.getId()))
                .andExpect(jsonPath("$.localNombre").value("Belgrano"))
                .andExpect(jsonPath("$.articulos[0].precio").value(1500.00));
        mvc.perform(get("/api/publico/catalogo/" + coco.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.precio").value(1500.00));
    }

    @Test
    void sinElegirLocalNiLocalCentralRespondeQueHayQueElegirUno() throws Exception {
        mvc.perform(get("/api/publico/catalogo"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value("Elegí un local: todavía no hay un local central."));
        mvc.perform(get("/api/publico/catalogo/" + coco.getId()))
                .andExpect(status().isBadRequest());
    }

    // --- Filtros y límites ---

    @Test
    void elListadoSePaginaConUnMaximoDeCincuenta() throws Exception {
        mvc.perform(get("/api/publico/catalogo").param("localId", palermo.getId().toString())
                        .param("tamano", "500"))
                .andExpect(jsonPath("$.tamano").value(50));
        mvc.perform(get("/api/publico/catalogo").param("localId", palermo.getId().toString())
                        .param("tamano", "1").param("pagina", "1"))
                .andExpect(jsonPath("$.pagina").value(1))
                .andExpect(jsonPath("$.tamano").value(1))
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.articulos[*].nombre").value(contains("Maceta soplada")));
        mvc.perform(get("/api/publico/catalogo").param("localId", palermo.getId().toString())
                        .param("pagina", "-3"))
                .andExpect(jsonPath("$.pagina").value(0))
                .andExpect(jsonPath("$.tamano").value(CatalogoPublicoService.TAMANO_POR_DEFECTO));
    }

    @Test
    void seBuscaPorNombreOMarcaYPorCategoria() throws Exception {
        String local = palermo.getId().toString();

        mvc.perform(get("/api/publico/catalogo").param("localId", local).param("q", "  MACETA "))
                .andExpect(jsonPath("$.articulos[*].nombre").value(contains("Maceta soplada")));
        mvc.perform(get("/api/publico/catalogo").param("localId", local).param("q", "green fire"))
                .andExpect(jsonPath("$.articulos[*].nombre").value(contains("Coco Elite")));
        mvc.perform(get("/api/publico/catalogo").param("localId", local)
                        .param("categoriaId", macetas.getId().toString()))
                .andExpect(jsonPath("$.articulos[*].nombre").value(contains("Maceta soplada")));
        // los comodines del visitante se buscan como texto
        mvc.perform(get("/api/publico/catalogo").param("localId", local).param("q", "%"))
                .andExpect(jsonPath("$.articulos").value(empty()));
        mvc.perform(get("/api/publico/catalogo").param("localId", local).param("q", "_"))
                .andExpect(jsonPath("$.articulos").value(empty()));
    }

    @Test
    void elTextoDeBusquedaTieneUnLargoMaximo() throws Exception {
        String local = palermo.getId().toString();

        mvc.perform(get("/api/publico/catalogo").param("localId", local).param("q", "a".repeat(100)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/publico/catalogo").param("localId", local).param("q", "a".repeat(101)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value("El texto de búsqueda admite hasta 100 caracteres."));
    }

    // --- Sin login, solo lectura y con caché corto ---

    @Test
    void losEndpointsPublicosNoPidenLoginYLlevanCacheCorto() throws Exception {
        for (String url : List.of(
                "/api/publico/categorias",
                "/api/publico/locales",
                "/api/publico/catalogo?localId=" + palermo.getId(),
                "/api/publico/catalogo/" + coco.getId() + "?localId=" + palermo.getId())) {
            mvc.perform(get(url))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Cache-Control", CACHE));
        }
    }

    @Test
    void losEndpointsPublicosSonDeSoloLectura() throws Exception {
        for (String url : List.of("/api/publico/categorias", "/api/publico/catalogo",
                "/api/publico/catalogo/" + coco.getId())) {
            mvc.perform(post(url).with(csrf())).andExpect(status().isMethodNotAllowed());
            mvc.perform(put(url).with(csrf())).andExpect(status().isMethodNotAllowed());
            mvc.perform(delete(url).with(csrf())).andExpect(status().isMethodNotAllowed());
        }
    }

    @Test
    void lasCategoriasInactivasNoAparecen() throws Exception {
        macetas.setActivo(false);

        JsonNode categorias = json("/api/publico/categorias");
        List<String> nombres = new ArrayList<>();
        categorias.forEach(c -> nombres.add(c.get("nombre").asText()));
        assertThat(nombres).contains(sustratos.getNombre()).doesNotContain(macetas.getNombre());
    }

    // --- Ayudas ---

    private Ambito visible(Ambito local) {
        local.setVisibleWeb(true);
        return ambitos.save(local);
    }

    private static Articulo publicable(Articulo a) {
        a.setVisibleWeb(true);
        return a;
    }

    private String texto(String url) throws Exception {
        return mvc.perform(get(url)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }

    private JsonNode json(String url) throws Exception {
        return mapper.readTree(texto(url));
    }

    private static Set<String> claves(JsonNode objeto) {
        Set<String> claves = new TreeSet<>();
        objeto.fieldNames().forEachRemaining(claves::add);
        return claves;
    }

    private static Set<String> todasLasClaves(JsonNode nodo) {
        Set<String> claves = new TreeSet<>();
        if (nodo.isObject()) {
            for (Map.Entry<String, JsonNode> campo : (Iterable<Map.Entry<String, JsonNode>>) nodo::fields) {
                claves.add(campo.getKey());
                claves.addAll(todasLasClaves(campo.getValue()));
            }
        } else if (nodo.isArray()) {
            nodo.forEach(hijo -> claves.addAll(todasLasClaves(hijo)));
        }
        return claves;
    }
}
