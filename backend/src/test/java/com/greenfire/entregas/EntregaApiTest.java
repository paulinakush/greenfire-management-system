package com.greenfire.entregas;

import static com.greenfire.Datos.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.greenfire.Datos;
import com.greenfire.PruebaIntegracion;
import com.greenfire.ambitos.Ambito;
import com.greenfire.auditoria.EventoAuditoria;
import com.greenfire.catalogo.Articulo;
import com.greenfire.catalogo.Categoria;
import com.greenfire.catalogo.TipoArticulo;
import com.greenfire.precios.ListaPrecios;
import com.greenfire.seguridad.Perfil;
import com.greenfire.stock.UbicacionStock;

import jakarta.persistence.EntityManager;

/** Entregas de la fábrica a los locales: el circuito, los permisos y lo que ve cada uno. */
@PruebaIntegracion
class EntregaApiTest {

    private static final Set<String> CLAVES_FABRICA = Set.of("id", "estado", "localId", "localNombre",
            "ubicacionOrigenId", "ubicacionOrigenNombre", "listaPreciosId", "listaPreciosNombre", "usuarioEnvioNombre",
            "fechaEnvio", "usuarioRecepcionNombre", "fechaRecepcion", "usuarioAnulacionNombre", "anuladaAt", "total",
            "observaciones", "observacionesRecepcion", "resolucionDiferencia", "motivoResolucion",
            "diferenciaResueltaAt", "renglones");
    private static final Set<String> CLAVES_LOCAL = Set.of("id", "estado", "origenNombre", "ubicacionDestinoId",
            "ubicacionDestinoNombre", "usuarioEnvioNombre", "fechaEnvio", "usuarioRecepcionNombre", "fechaRecepcion",
            "anuladaAt", "total", "observaciones", "observacionesRecepcion", "resolucionDiferencia",
            "motivoResolucion", "diferenciaResueltaAt", "renglones");
    private static final Set<String> CLAVES_EMPLEADO = Set.of("id", "estado", "origenNombre", "ubicacionDestinoId",
            "ubicacionDestinoNombre", "fechaEnvio", "usuarioRecepcionNombre", "fechaRecepcion", "observaciones",
            "observacionesRecepcion", "renglones");
    private static final Set<String> RENGLON_CON_PRECIO = Set.of("id", "articuloId", "articuloNombre", "unidadMedida",
            "loteId", "cantidadEnviada", "cantidadRecibida", "precioUnitario", "subtotal");
    private static final Set<String> RENGLON_SIN_PRECIO = Set.of("id", "articuloId", "articuloNombre", "unidadMedida",
            "loteId", "cantidadEnviada", "cantidadRecibida");

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

    final ObjectMapper mapper = new ObjectMapper();

    Ambito fabrica;
    Ambito centro;
    Ambito norte;
    Categoria categoria;
    Articulo coco;
    Articulo turba;
    ListaPrecios lista;
    UbicacionStock origen;
    UbicacionStock destino;
    RequestPostProcessor duenoFabrica;
    RequestPostProcessor duenoCentro;
    RequestPostProcessor empleadoCentro;
    RequestPostProcessor duenoNorte;

    @BeforeEach
    void datos() {
        fabrica = datos.fabrica();
        centro = datos.local("Centro");
        norte = datos.local("Norte");
        categoria = datos.categoria("Sustratos");
        coco = datos.articulo(fabrica, TipoArticulo.producto_terminado, "Coco", categoria);
        turba = datos.articulo(fabrica, TipoArticulo.producto_terminado, "Turba", categoria);
        lista = datos.lista(fabrica, "Para locales", false, true);
        datos.precio(lista, coco, "1000");
        datos.precio(lista, turba, "250.50");
        origen = datos.principal(fabrica);
        destino = datos.principal(centro);
        datos.stock(coco, origen, "20");
        datos.stock(turba, origen, "10");
        duenoFabrica = datos.en(fabrica, Perfil.dueno_fabrica);
        duenoCentro = datos.en(centro, Perfil.dueno_local);
        empleadoCentro = datos.en(centro, Perfil.empleado_local);
        duenoNorte = datos.en(norte, Perfil.dueno_local);
    }

    // --- Envío ---

    @Test
    void elDuenoDeFabricaEnviaConElPrecioDeLaListaYSaleElStock() throws Exception {
        JsonNode e = cuerpo(enviar(duenoFabrica, renglon(coco, "3"), renglon(turba, "2"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.estado").value("enviada"))
                .andExpect(jsonPath("$.renglones[0].precioUnitario").value(1000.00))
                .andExpect(jsonPath("$.renglones[0].subtotal").value(3000.00))
                .andExpect(jsonPath("$.renglones[1].precioUnitario").value(250.50))
                .andExpect(jsonPath("$.renglones[1].subtotal").value(501.00))
                .andExpect(jsonPath("$.total").value(3501.00)));

        assertThat(datos.cantidad(coco, origen)).isEqualByComparingTo("17");
        assertThat(datos.cantidad(turba, origen)).isEqualByComparingTo("8");
        assertThat(jdbc.queryForList("select tipo from movimientos_stock where entrega_id = ?", String.class,
                e.get("id").asLong())).containsExactly("entrega_salida", "entrega_salida");
        assertThat(jdbc.queryForObject("select total from entregas where id = ?", BigDecimal.class,
                e.get("id").asLong())).isEqualByComparingTo("3501");

        List<EventoAuditoria> registros = auditorias("enviar");
        assertThat(registros).extracting(EventoAuditoria::ambitosAfectados)
                .containsExactly(List.of(fabrica.getId()), List.of(centro.getId()));
        // el registro del local lo ven los dos: no lleva la ubicación ni la lista de la fábrica
        assertThat(registros.get(0).detalle()).containsKeys("ubicacionOrigenId", "listaPreciosId", "movimientos");
        assertThat(registros.get(1).detalle()).containsOnlyKeys("localId", "total", "renglones");
    }

    @Test
    void soloSeEntreganProductosTerminadosDeLaFabricaConPrecioEnLaLista() throws Exception {
        Articulo perlita = datos.articulo(fabrica, TipoArticulo.insumo, "Perlita", null);
        Articulo sinPrecio = datos.articulo(fabrica, TipoArticulo.producto_terminado, "Sin precio", categoria);
        Articulo inactivo = datos.articulo(fabrica, TipoArticulo.producto_terminado, "Inactivo", categoria);
        datos.precio(lista, inactivo, "10");
        inactivo.setActivo(false);
        Articulo macetaCentro = datos.articulo(centro, TipoArticulo.reventa, "Maceta", datos.categoria("Macetas"));

        enviar(duenoFabrica, renglon(perlita, "1")).andExpect(status().isConflict());
        enviar(duenoFabrica, renglon(sinPrecio, "1")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.mensaje").value("'Sin precio' no tiene precio en la lista 'Para locales'."));
        enviar(duenoFabrica, renglon(inactivo, "1")).andExpect(status().isConflict());
        enviar(duenoFabrica, renglon(coco, "1"), renglon(coco, "2")).andExpect(status().isConflict());
        // la reventa de un local la fábrica ni la ve
        enviar(duenoFabrica, renglon(macetaCentro, "1")).andExpect(status().isNotFound());

        assertThat(jdbc.queryForObject("select count(*) from entregas", Integer.class)).isZero();
    }

    @Test
    void laListaYLaUbicacionDeOrigenSonDeLaFabricaYElDestinoUnLocal() throws Exception {
        ListaPrecios listaDelLocal = datos.lista(centro, "Público", true, true);

        enviar(duenoFabrica, centro.getId(), destino.getId(), lista.getId(), renglon(coco, "1"))
                .andExpect(status().isNotFound());
        enviar(duenoFabrica, centro.getId(), origen.getId(), listaDelLocal.getId(), renglon(coco, "1"))
                .andExpect(status().isNotFound());
        enviar(duenoFabrica, fabrica.getId(), origen.getId(), lista.getId(), renglon(coco, "1"))
                .andExpect(status().isNotFound());
    }

    // --- Permisos ---

    @Test
    void soloElDuenoDeFabricaEnviaAnulaYResuelve() throws Exception {
        long id = enviada();
        for (RequestPostProcessor sesion : List.of(duenoCentro, empleadoCentro, datos.admin())) {
            enviar(sesion, renglon(coco, "1")).andExpect(status().isForbidden());
            mvc.perform(post("/api/entregas/" + id + "/anular").with(sesion)).andExpect(status().isForbidden());
            mvc.perform(json(post("/api/entregas/" + id + "/resolver-diferencia"), sesion,
                    "{\"forma\": \"perdida\", \"motivo\": \"Rotura\"}")).andExpect(status().isForbidden());
        }
        mvc.perform(json(post("/api/entregas/" + id + "/recepcion"), duenoFabrica, recepcion(id, destino, "1")))
                .andExpect(status().isForbidden());
    }

    @Test
    void elAdministradorTecnicoNoVeEntregas() throws Exception {
        long id = enviada();
        mvc.perform(get("/api/entregas").with(datos.admin())).andExpect(status().isForbidden());
        mvc.perform(get("/api/entregas/" + id).with(datos.admin())).andExpect(status().isForbidden());
        mvc.perform(json(post("/api/entregas/" + id + "/recepcion"), datos.admin(), recepcion(id, destino, "1")))
                .andExpect(status().isForbidden());
    }

    @Test
    void otroLocalNoVeNiRecibeLaEntrega() throws Exception {
        long id = enviada();
        RequestPostProcessor empleadoNorte = datos.en(norte, Perfil.empleado_local);

        mvc.perform(get("/api/entregas/" + id).with(duenoNorte)).andExpect(status().isNotFound());
        mvc.perform(get("/api/entregas/" + id).with(empleadoNorte)).andExpect(status().isNotFound());
        mvc.perform(get("/api/entregas").with(duenoNorte)).andExpect(jsonPath("$").value(empty()));
        mvc.perform(json(post("/api/entregas/" + id + "/recepcion"), duenoNorte,
                recepcion(id, datos.principal(norte), "1"))).andExpect(status().isNotFound());
    }

    // --- Lo que ve cada uno ---

    @Test
    void elEmpleadoRecibeConUnaVistaSinPreciosNiTotales() throws Exception {
        long id = enviada();

        JsonNode antes = cuerpo(mvc.perform(get("/api/entregas/" + id).with(empleadoCentro)).andExpect(status().isOk()));
        assertThat(claves(antes)).isEqualTo(CLAVES_EMPLEADO);
        assertThat(claves(antes.get("renglones").get(0))).isEqualTo(RENGLON_SIN_PRECIO);

        JsonNode despues = cuerpo(mvc.perform(json(post("/api/entregas/" + id + "/recepcion"), empleadoCentro,
                        recepcion(id, destino, "3")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("recibida")));
        assertThat(claves(despues)).isEqualTo(CLAVES_EMPLEADO);
        assertThat(claves(despues.get("renglones").get(0))).isEqualTo(RENGLON_SIN_PRECIO);

        JsonNode listado = cuerpo(mvc.perform(get("/api/entregas").with(empleadoCentro)));
        assertThat(claves(listado.get(0))).isEqualTo(CLAVES_EMPLEADO);
        assertThat(datos.cantidad(coco, destino)).isEqualByComparingTo("3");
    }

    @Test
    void elDuenoDelLocalVeLosPreciosDeLoQueRecibePeroNadaDelStockDeLaFabrica() throws Exception {
        long id = enviada();

        JsonNode e = cuerpo(mvc.perform(get("/api/entregas/" + id).with(duenoCentro))
                .andExpect(jsonPath("$.total").value(3000.00))
                .andExpect(jsonPath("$.renglones[0].precioUnitario").value(1000.00)));
        assertThat(claves(e)).isEqualTo(CLAVES_LOCAL);
        assertThat(claves(e.get("renglones").get(0))).isEqualTo(RENGLON_CON_PRECIO);
        assertThat(claves(cuerpo(mvc.perform(get("/api/entregas").with(duenoCentro))).get(0))).isEqualTo(CLAVES_LOCAL);
    }

    @Test
    void laFabricaVeLasQueEnvioSinNadaDelStockDelLocal() throws Exception {
        long id = enviada();
        recibir(empleadoCentro, id, destino, "3").andExpect(status().isOk());

        JsonNode e = cuerpo(mvc.perform(get("/api/entregas/" + id).with(duenoFabrica))
                .andExpect(jsonPath("$.estado").value("recibida")));
        assertThat(claves(e)).isEqualTo(CLAVES_FABRICA);
        assertThat(claves(e.get("renglones").get(0))).isEqualTo(RENGLON_CON_PRECIO);

        // de los registros de la recepción, la fábrica ve el suyo: sin la ubicación, los movimientos ni los costos
        List<EventoAuditoria> registros = auditorias("recibir");
        assertThat(registros).extracting(EventoAuditoria::ambitosAfectados)
                .containsExactly(List.of(fabrica.getId()), List.of(centro.getId()));
        assertThat(registros.get(0).detalle()).containsOnlyKeys("estado", "renglones", "observaciones");
        assertThat(registros.get(1).detalle()).containsKeys("ubicacionDestinoId", "movimientos", "costos");
    }

    // --- Recepción ---

    @Test
    void laRecepcionEntraElStockYElCostoPasaASerElPrecioDeLaEntrega() throws Exception {
        long id = enviada();
        recibir(duenoCentro, id, destino, "3").andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("recibida"))
                .andExpect(jsonPath("$.ubicacionDestinoNombre").value("Principal"));

        assertThat(datos.cantidad(coco, destino)).isEqualByComparingTo("3");
        assertThat(jdbc.queryForList("select tipo from movimientos_stock where entrega_id = ? order by id",
                String.class, id)).containsExactly("entrega_salida", "entrega_recepcion");
        assertThat(costo(coco, centro)).isEqualByComparingTo("1000");
        mvc.perform(get("/api/articulos/" + coco.getId()).with(duenoCentro))
                .andExpect(jsonPath("$.costo").value(1000.00));

        // la próxima entrega, con otro precio, actualiza el costo
        ListaPrecios otraLista = datos.lista(fabrica, "Otra", false, false);
        datos.precio(otraLista, coco, "1200");
        long otra = enviada(otraLista.getId());
        recibir(duenoCentro, otra, destino, "3").andExpect(status().isOk());
        assertThat(costo(coco, centro)).isEqualByComparingTo("1200");
    }

    @Test
    void noSeRecibeMasDeLoEnviado() throws Exception {
        long id = enviada();
        recibir(duenoCentro, id, destino, "4")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.mensaje").value("De 'Coco' se enviaron 3: no se pueden recibir 4."));

        assertThat(jdbc.queryForObject("select estado from entregas where id = ?", String.class, id))
                .isEqualTo("enviada");
        assertThat(datos.cantidad(coco, destino)).isEqualByComparingTo("0");
    }

    @Test
    void laUbicacionDeDestinoTieneQueSerDelLocal() throws Exception {
        long id = enviada();
        recibir(duenoCentro, id, datos.principal(norte), "3").andExpect(status().isNotFound());
        recibir(duenoCentro, id, origen, "3").andExpect(status().isNotFound());
    }

    @Test
    void conFaltanteQuedaConDiferenciaInformada() throws Exception {
        long id = cuerpo(enviar(duenoFabrica, renglon(coco, "3"), renglon(turba, "2"))).get("id").asLong();
        JsonNode e = cuerpo(mvc.perform(get("/api/entregas/" + id).with(duenoCentro)));
        long renglonCoco = e.get("renglones").get(0).get("id").asLong();
        long renglonTurba = e.get("renglones").get(1).get("id").asLong();

        mvc.perform(json(post("/api/entregas/" + id + "/recepcion"), duenoCentro, """
                        {"ubicacionId": %d, "observaciones": "Llegó una bolsa rota y faltó la turba",
                         "renglones": [{"renglonId": %d, "cantidadRecibida": 2}, {"renglonId": %d, "cantidadRecibida": 0}]}
                        """.formatted(destino.getId(), renglonCoco, renglonTurba)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("diferencia_informada"))
                .andExpect(jsonPath("$.observacionesRecepcion").value("Llegó una bolsa rota y faltó la turba"));

        assertThat(datos.cantidad(coco, destino)).isEqualByComparingTo("2");
        assertThat(datos.cantidad(turba, destino)).isEqualByComparingTo("0");
        assertThat(costo(coco, centro)).isEqualByComparingTo("1000");
        assertThat(costo(turba, centro)).isNull(); // no llegó: no cambia el costo
    }

    @Test
    void laRecepcionTieneQueTraerCadaRenglonUnaVez() throws Exception {
        long id = cuerpo(enviar(duenoFabrica, renglon(coco, "3"), renglon(turba, "2"))).get("id").asLong();
        long renglonCoco = cuerpo(mvc.perform(get("/api/entregas/" + id).with(duenoCentro)))
                .get("renglones").get(0).get("id").asLong();

        mvc.perform(json(post("/api/entregas/" + id + "/recepcion"), duenoCentro, """
                        {"ubicacionId": %d, "renglones": [{"renglonId": %d, "cantidadRecibida": 3}]}
                        """.formatted(destino.getId(), renglonCoco)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.mensaje").value("Falta la cantidad recibida de 'Turba'."));
        mvc.perform(json(post("/api/entregas/" + id + "/recepcion"), duenoCentro, """
                        {"ubicacionId": %d, "renglones": [{"renglonId": 999999999, "cantidadRecibida": 3}]}
                        """.formatted(destino.getId())))
                .andExpect(status().isConflict());
    }

    @Test
    void soloSeRecibeUnaVez() throws Exception {
        long id = enviada();
        recibir(duenoCentro, id, destino, "3").andExpect(status().isOk());
        recibir(empleadoCentro, id, destino, "3").andExpect(status().isConflict());
        assertThat(datos.cantidad(coco, destino)).isEqualByComparingTo("3");
    }

    // --- Anulación ---

    @Test
    void anularDevuelveElStockAlOrigenYDespuesNoSeRecibe() throws Exception {
        long id = cuerpo(enviar(duenoFabrica, renglon(coco, "3"), renglon(turba, "2"))).get("id").asLong();

        mvc.perform(post("/api/entregas/" + id + "/anular").with(duenoFabrica))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("anulada"));

        assertThat(datos.cantidad(coco, origen)).isEqualByComparingTo("20");
        assertThat(datos.cantidad(turba, origen)).isEqualByComparingTo("10");
        assertThat(jdbc.queryForList("select tipo from movimientos_stock where entrega_id = ? order by id",
                String.class, id)).containsExactly("entrega_salida", "entrega_salida", "anulacion", "anulacion");
        assertThat(auditorias("anular")).extracting(EventoAuditoria::ambitosAfectados)
                .containsExactly(List.of(fabrica.getId()), List.of(centro.getId()));
        recibir(duenoCentro, id, destino, "3").andExpect(status().isConflict());
        mvc.perform(post("/api/entregas/" + id + "/anular").with(duenoFabrica)).andExpect(status().isConflict());
    }

    @Test
    void noSeAnulaUnaEntregaRecibida() throws Exception {
        long id = enviada();
        recibir(duenoCentro, id, destino, "3").andExpect(status().isOk());

        mvc.perform(post("/api/entregas/" + id + "/anular").with(duenoFabrica)).andExpect(status().isConflict());
        assertThat(datos.cantidad(coco, origen)).isEqualByComparingTo("17");
        assertThat(datos.cantidad(coco, destino)).isEqualByComparingTo("3");
    }

    // --- Resolución de la diferencia ---

    @Test
    void reingresarElFaltanteLoDevuelveALaUbicacionDeOrigen() throws Exception {
        long id = conFaltante("10", "7");

        resolver(id, "reingreso", "Volvió en el camión").andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("diferencia_informada"))
                .andExpect(jsonPath("$.resolucionDiferencia").value("reingreso"))
                .andExpect(jsonPath("$.motivoResolucion").value("Volvió en el camión"))
                .andExpect(jsonPath("$.diferenciaResueltaAt").isNotEmpty());

        assertThat(datos.cantidad(coco, origen)).isEqualByComparingTo("13");
        assertThat(jdbc.queryForList("select tipo || ' ' || cantidad from movimientos_stock where entrega_id = ? order by id",
                String.class, id)).containsExactly("entrega_salida -10.000", "entrega_recepcion 7.000",
                        "devolucion 3.000");
        assertThat(auditorias("resolver_diferencia")).extracting(EventoAuditoria::ambitosAfectados)
                .containsExactly(List.of(fabrica.getId()), List.of(centro.getId()));
    }

    @Test
    void darPorPerdidoElFaltanteNoMueveStock() throws Exception {
        long id = conFaltante("10", "7");

        resolver(id, "perdida", "Se rompió en el viaje").andExpect(status().isOk())
                .andExpect(jsonPath("$.resolucionDiferencia").value("perdida"));

        assertThat(datos.cantidad(coco, origen)).isEqualByComparingTo("10");
        assertThat(jdbc.queryForObject("select count(*) from movimientos_stock where entrega_id = ? and tipo = 'devolucion'",
                Integer.class, id)).isZero();
    }

    @Test
    void laResolucionExigeMotivoYNoSeRepite() throws Exception {
        long id = conFaltante("10", "7");

        resolver(id, "perdida", "  ").andExpect(status().isBadRequest());
        mvc.perform(json(post("/api/entregas/" + id + "/resolver-diferencia"), duenoFabrica, "{\"forma\": \"perdida\"}"))
                .andExpect(status().isBadRequest());
        resolver(id, "perdida", "Rotura").andExpect(status().isOk());
        resolver(id, "reingreso", "Apareció").andExpect(status().isConflict())
                .andExpect(jsonPath("$.mensaje").value("La diferencia de la entrega ya está resuelta."));
        assertThat(datos.cantidad(coco, origen)).isEqualByComparingTo("10");
    }

    @Test
    void sinDiferenciaNoHayNadaQueResolver() throws Exception {
        long id = enviada();
        resolver(id, "perdida", "Rotura").andExpect(status().isConflict());
        recibir(duenoCentro, id, destino, "3").andExpect(status().isOk());
        resolver(id, "perdida", "Rotura").andExpect(status().isConflict());
    }

    // --- Consistencia ---

    @Test
    void elStockEntreOrigenDestinoYLoPerdidoCoincideConLoEnviado() throws Exception {
        BigDecimal inicial = datos.cantidad(coco, origen);
        long id = conFaltante("10", "7");
        resolver(id, "perdida", "Rotura").andExpect(status().isOk());

        BigDecimal enOrigen = datos.cantidad(coco, origen);
        BigDecimal enDestino = datos.cantidad(coco, destino);
        BigDecimal perdido = jdbc.queryForObject(
                "select sum(cantidad_enviada - cantidad_recibida) from detalle_entregas where entrega_id = ?",
                BigDecimal.class, id);
        BigDecimal enviado = jdbc.queryForObject(
                "select sum(cantidad_enviada) from detalle_entregas where entrega_id = ?", BigDecimal.class, id);

        assertThat(enDestino.add(perdido)).isEqualByComparingTo(enviado);
        assertThat(enOrigen.add(enDestino).add(perdido)).isEqualByComparingTo(inicial);
        // con reingreso, lo que vuelve también cierra la cuenta
        long otra = conFaltante("5", "1");
        resolver(otra, "reingreso", "Volvió").andExpect(status().isOk());
        assertThat(datos.cantidad(coco, origen).add(datos.cantidad(coco, destino)).add(perdido))
                .isEqualByComparingTo(inicial);
        // y el caché del stock es la suma de los movimientos
        assertThat(jdbc.queryForObject("""
                select count(*) from stock_articulos s
                where s.cantidad_actual <> (select coalesce(sum(m.cantidad), 0) from movimientos_stock m
                                            where m.articulo_id = s.articulo_id
                                              and m.ubicacion_stock_id = s.ubicacion_stock_id)
                """, Integer.class)).isZero();
    }

    // --- Ayudas ---

    private ResultActions enviar(RequestPostProcessor sesion, String... renglones) throws Exception {
        return enviar(sesion, centro.getId(), origen.getId(), lista.getId(), renglones);
    }

    private ResultActions enviar(RequestPostProcessor sesion, long local, long ubicacion, long listaId,
            String... renglones) throws Exception {
        return mvc.perform(json(post("/api/entregas"), sesion, """
                {"localId": %d, "ubicacionOrigenId": %d, "listaPreciosId": %d, "renglones": [%s]}
                """.formatted(local, ubicacion, listaId, String.join(", ", renglones))));
    }

    private static String renglon(Articulo articulo, String cantidad) {
        return "{\"articuloId\": %d, \"cantidad\": %s}".formatted(articulo.getId(), cantidad);
    }

    /** Una entrega de 3 de coco, enviada. */
    private long enviada() throws Exception {
        return enviada(lista.getId());
    }

    private long enviada(long listaId) throws Exception {
        return cuerpo(enviar(duenoFabrica, centro.getId(), origen.getId(), listaId, renglon(coco, "3"))
                .andExpect(status().isCreated())).get("id").asLong();
    }

    /** Una entrega de coco con faltante, recibida en Principal del Centro. */
    private long conFaltante(String enviado, String recibido) throws Exception {
        long id = cuerpo(enviar(duenoFabrica, renglon(coco, enviado)).andExpect(status().isCreated()))
                .get("id").asLong();
        recibir(duenoCentro, id, destino, recibido).andExpect(jsonPath("$.estado").value("diferencia_informada"));
        return id;
    }

    /** La recepción de una entrega de un solo renglón. */
    private String recepcion(long id, UbicacionStock ubicacion, String cantidad) {
        Long renglon = jdbc.queryForObject("select min(id) from detalle_entregas where entrega_id = ?", Long.class, id);
        return """
                {"ubicacionId": %d, "renglones": [{"renglonId": %d, "cantidadRecibida": %s}]}
                """.formatted(ubicacion.getId(), renglon, cantidad);
    }

    private ResultActions recibir(RequestPostProcessor sesion, long id, UbicacionStock ubicacion, String cantidad)
            throws Exception {
        return mvc.perform(json(post("/api/entregas/" + id + "/recepcion"), sesion, recepcion(id, ubicacion, cantidad)));
    }

    private ResultActions resolver(long id, String forma, String motivo) throws Exception {
        return mvc.perform(json(post("/api/entregas/" + id + "/resolver-diferencia"), duenoFabrica,
                "{\"forma\": \"%s\", \"motivo\": \"%s\"}".formatted(forma, motivo)));
    }

    private BigDecimal costo(Articulo articulo, Ambito ambito) {
        em.flush();
        return jdbc.query("select costo_actual from costos_articulo where articulo_id = ? and ambito_id = ?",
                rs -> rs.next() ? rs.getBigDecimal(1) : null, articulo.getId(), ambito.getId());
    }

    private List<EventoAuditoria> auditorias(String accion) {
        return eventos.stream(EventoAuditoria.class)
                .filter(e -> e.accion().equals(accion) && e.entidad().equals("entrega")).toList();
    }

    private JsonNode cuerpo(ResultActions resultado) throws Exception {
        return mapper.readTree(resultado.andReturn().getResponse().getContentAsString());
    }

    private static Set<String> claves(JsonNode objeto) {
        Set<String> claves = new TreeSet<>();
        objeto.fieldNames().forEachRemaining(claves::add);
        return claves;
    }
}
