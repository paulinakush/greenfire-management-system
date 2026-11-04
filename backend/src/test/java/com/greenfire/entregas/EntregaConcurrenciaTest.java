package com.greenfire.entregas;

import static com.greenfire.Datos.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.greenfire.Datos;
import com.greenfire.DatosConfirmados;
import com.greenfire.PruebaIntegracion;
import com.greenfire.ambitos.Ambito;
import com.greenfire.catalogo.Articulo;
import com.greenfire.catalogo.Categoria;
import com.greenfire.catalogo.TipoArticulo;
import com.greenfire.precios.ListaPrecios;
import com.greenfire.seguridad.Perfil;
import com.greenfire.stock.UbicacionStock;

/**
 * Entregas con transacciones reales, que se confirman o se revierten de verdad (NOT_SUPPORTED): el todo o nada y
 * las operaciones simultáneas sobre una misma entrega o un mismo stock. Lo que dejan guardado lo borra
 * {@link DatosConfirmados}. Al confirmar corre además la verificación diferida del total (V9).
 */
@PruebaIntegracion
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class EntregaConcurrenciaTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    Datos datos;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TransactionTemplate transaccion;
    @Autowired
    ObjectMapper mapper;

    ExecutorService hilos = Executors.newFixedThreadPool(2);
    DatosConfirmados confirmados;
    Ambito fabrica;
    Ambito local;
    Articulo coco;
    Articulo turba;
    ListaPrecios lista;
    UbicacionStock origen;
    UbicacionStock destino;
    RequestPostProcessor duenoFabrica;
    RequestPostProcessor duenoLocal;
    RequestPostProcessor empleadoLocal;

    @BeforeEach
    void datos() {
        confirmados = new DatosConfirmados(jdbc, transaccion);
        fabrica = datos.fabrica();
        local = datos.local("Entregas");
        Categoria categoria = datos.categoria("Sustratos");
        coco = datos.articulo(fabrica, TipoArticulo.producto_terminado, "Coco", categoria);
        turba = datos.articulo(fabrica, TipoArticulo.producto_terminado, "Turba", categoria);
        lista = datos.lista(fabrica, "Para locales", false, true);
        datos.precio(lista, coco, "1000");
        datos.precio(lista, turba, "500");
        origen = datos.principal(fabrica);
        destino = datos.principal(local);
        duenoFabrica = datos.en(fabrica, Perfil.dueno_fabrica);
        duenoLocal = datos.en(local, Perfil.dueno_local);
        empleadoLocal = datos.en(local, Perfil.empleado_local);
    }

    @AfterEach
    void limpiar() {
        hilos.shutdownNow();
        confirmados.limpiar();
    }

    @Test
    void unaEntregaQueFallaEnUnRenglonNoMueveNada() throws Exception {
        datos.stock(coco, origen, "20");
        datos.stock(turba, origen, "10");

        MockHttpServletResponse r = envio(renglon(coco, "3"), renglon(turba, "50")).call();

        assertThat(r.getStatus()).isEqualTo(409);
        assertThat(mapper.readTree(r.getContentAsString()).get("mensaje").asText())
                .isEqualTo("Stock insuficiente: 'Turba' en 'Principal' tiene 10 y se quieren sacar 50.");
        assertThat(datos.cantidad(coco, origen)).isEqualByComparingTo("20");
        assertThat(datos.cantidad(turba, origen)).isEqualByComparingTo("10");
        assertThat(nuevas("entregas")).isZero();
        assertThat(nuevas("detalle_entregas")).isZero();
        assertThat(jdbc.queryForObject("select count(*) from movimientos_stock where id > ? and entrega_id is not null",
                Integer.class, confirmados.ultimoId("movimientos_stock"))).isZero();
        assertThat(jdbc.queryForObject("select count(*) from auditorias where id > ? and entidad = 'entrega'",
                Integer.class, confirmados.ultimoId("auditorias"))).isZero();

        // con stock suficiente, la misma entrega se envía y se confirma (pasa la verificación diferida del total)
        assertThat(envio(renglon(coco, "3"), renglon(turba, "10")).call().getStatus()).isEqualTo(201);
        assertThat(datos.cantidad(coco, origen)).isEqualByComparingTo("17");
        assertThat(datos.cantidad(turba, origen)).isZero();
    }

    @Test
    void dosRecepcionesSimultaneasUnaGanaYLaOtraRecibe409() throws Exception {
        datos.stock(coco, origen, "20");
        long id = enviada("3");
        CyclicBarrier largada = new CyclicBarrier(2);

        Future<MockHttpServletResponse> a = hilos.submit(enLaLargada(largada, recepcion(duenoLocal, id, "3")));
        Future<MockHttpServletResponse> b = hilos.submit(enLaLargada(largada, recepcion(empleadoLocal, id, "2")));

        List<MockHttpServletResponse> respuestas = List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
        assertThat(respuestas).extracting(MockHttpServletResponse::getStatus).containsExactlyInAnyOrder(200, 409);
        assertThat(jdbc.queryForObject("select count(*) from movimientos_stock where entrega_id = ? and tipo = 'entrega_recepcion'",
                Integer.class, id)).isEqualTo(1);
        // entró lo de la recepción que ganó, una sola vez
        String estado = jdbc.queryForObject("select estado from entregas where id = ?", String.class, id);
        assertThat(datos.cantidad(coco, destino)).isEqualByComparingTo(estado.equals("recibida") ? "3" : "2");
    }

    @Test
    void laSegundaRecepcionEsperaElBloqueoDeLaEntregaYDespuesRecibe409() throws Exception {
        datos.stock(coco, origen, "20");
        long id = enviada("3");
        CountDownLatch primeraTieneElBloqueo = new CountDownLatch(1);
        CountDownLatch soltar = new CountDownLatch(1);

        // otra transacción tiene bloqueada la entrega, como una recepción o una anulación en curso
        Future<?> primera = hilos.submit(() -> transaccion.executeWithoutResult(estado -> {
            jdbc.queryForObject("select id from entregas where id = ? for update", Long.class, id);
            primeraTieneElBloqueo.countDown();
            esperar(soltar);
        }));
        assertThat(primeraTieneElBloqueo.await(10, TimeUnit.SECONDS)).isTrue();

        Future<MockHttpServletResponse> segunda = hilos.submit(recepcion(duenoLocal, id, "3"));
        assertThatThrownBy(() -> segunda.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);

        soltar.countDown();
        primera.get(10, TimeUnit.SECONDS);
        assertThat(segunda.get(10, TimeUnit.SECONDS).getStatus()).isEqualTo(200);
        // una vez recibida, otra recepción responde 409
        assertThat(recepcion(empleadoLocal, id, "3").call().getStatus()).isEqualTo(409);
        assertThat(datos.cantidad(coco, destino)).isEqualByComparingTo("3");
    }

    @Test
    void anularALaVezQueSeRecibeDejaUnSoloResultadoConsistente() throws Exception {
        datos.stock(coco, origen, "20");
        long id = enviada("3");
        CyclicBarrier largada = new CyclicBarrier(2);

        Future<MockHttpServletResponse> anulacion = hilos.submit(enLaLargada(largada,
                () -> mvc.perform(post("/api/entregas/" + id + "/anular").with(duenoFabrica)).andReturn().getResponse()));
        Future<MockHttpServletResponse> recepcion = hilos.submit(enLaLargada(largada, recepcion(duenoLocal, id, "3")));

        int anulo = anulacion.get(10, TimeUnit.SECONDS).getStatus();
        int recibio = recepcion.get(10, TimeUnit.SECONDS).getStatus();
        assertThat(List.of(anulo, recibio)).containsExactlyInAnyOrder(200, 409);

        String estado = jdbc.queryForObject("select estado from entregas where id = ?", String.class, id);
        if (anulo == 200) {
            assertThat(estado).isEqualTo("anulada");
            assertThat(datos.cantidad(coco, origen)).isEqualByComparingTo("20");
            assertThat(datos.cantidad(coco, destino)).isZero();
        } else {
            assertThat(estado).isEqualTo("recibida");
            assertThat(datos.cantidad(coco, origen)).isEqualByComparingTo("17");
            assertThat(datos.cantidad(coco, destino)).isEqualByComparingTo("3");
        }
    }

    @Test
    void dosEnviosQueCompitenPorElMismoStockNoLoDejanNegativo() throws Exception {
        datos.stock(coco, origen, "5");
        CyclicBarrier largada = new CyclicBarrier(2);

        Future<MockHttpServletResponse> a = hilos.submit(enLaLargada(largada, envio(renglon(coco, "4"))));
        Future<MockHttpServletResponse> b = hilos.submit(enLaLargada(largada, envio(renglon(coco, "4"))));

        assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)))
                .extracting(MockHttpServletResponse::getStatus).containsExactlyInAnyOrder(201, 409);
        assertThat(datos.cantidad(coco, origen)).isEqualByComparingTo("1");
        assertThat(nuevas("entregas")).isEqualTo(1);
    }

    // --- Ayudas ---

    private int nuevas(String tabla) {
        return jdbc.queryForObject("select count(*) from " + tabla + " where id > ?", Integer.class,
                confirmados.ultimoId(tabla));
    }

    private Callable<MockHttpServletResponse> envio(String... renglones) {
        return () -> mvc.perform(json(post("/api/entregas"), duenoFabrica, """
                {"localId": %d, "ubicacionOrigenId": %d, "listaPreciosId": %d, "renglones": [%s]}
                """.formatted(local.getId(), origen.getId(), lista.getId(), String.join(", ", renglones))))
                .andReturn().getResponse();
    }

    private static String renglon(Articulo articulo, String cantidad) {
        return "{\"articuloId\": %d, \"cantidad\": %s}".formatted(articulo.getId(), cantidad);
    }

    private long enviada(String cantidad) throws Exception {
        MockHttpServletResponse r = envio(renglon(coco, cantidad)).call();
        assertThat(r.getStatus()).isEqualTo(201);
        return mapper.readTree(r.getContentAsString()).get("id").asLong();
    }

    private Callable<MockHttpServletResponse> recepcion(RequestPostProcessor sesion, long id, String cantidad) {
        Long renglon = jdbc.queryForObject("select min(id) from detalle_entregas where entrega_id = ?", Long.class, id);
        return () -> mvc.perform(json(post("/api/entregas/" + id + "/recepcion"), sesion, """
                {"ubicacionId": %d, "renglones": [{"renglonId": %d, "cantidadRecibida": %s}]}
                """.formatted(destino.getId(), renglon, cantidad))).andReturn().getResponse();
    }

    private static <T> Callable<T> enLaLargada(CyclicBarrier largada, Callable<T> tarea) {
        return () -> {
            largada.await(10, TimeUnit.SECONDS);
            return tarea.call();
        };
    }

    private static void esperar(CountDownLatch senal) {
        try {
            senal.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
