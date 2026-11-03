package com.greenfire.stock;

import static com.greenfire.Datos.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import com.greenfire.PruebaIntegracion;
import com.greenfire.ambitos.Ambito;
import com.greenfire.catalogo.Articulo;
import com.greenfire.catalogo.Categoria;
import com.greenfire.catalogo.TipoArticulo;
import com.greenfire.seguridad.Perfil;

/**
 * Operaciones con transacciones reales, que se confirman o se revierten de verdad: este test no corre dentro de
 * la transacción del test (NOT_SUPPORTED). Lo que deja guardado lo borra {@link #limpiar()}, con los triggers
 * desactivados, porque los movimientos son solo de inserción.
 */
@PruebaIntegracion
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class StockConcurrenciaTest {

    /** Tablas con datos de estos tests, en orden de borrado. */
    private static final List<String> TABLAS = List.of("movimientos_stock", "stock_articulos", "ubicaciones_stock",
            "articulos", "categorias", "auditorias", "usuario_ambitos", "usuarios", "ambitos", "entidades_fiscales");

    @Autowired
    MockMvc mvc;
    @Autowired
    Datos datos;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TransactionTemplate transaccion;
    @Autowired
    RegistroStock registro;
    @Autowired
    ObjectMapper mapper;

    ExecutorService hilos = Executors.newFixedThreadPool(2);
    Map<String, Long> ultimoId = new HashMap<>();

    Ambito local;
    Categoria categoria;
    UbicacionStock principal;

    @BeforeEach
    void datos() {
        for (String tabla : TABLAS) {
            ultimoId.put(tabla, jdbc.queryForObject("select coalesce(max(id), 0) from " + tabla, Long.class));
        }
        local = datos.local("Concurrencia");
        categoria = datos.categoria("Macetas");
        principal = datos.principal(local);
    }

    @AfterEach
    void limpiar() {
        hilos.shutdownNow();
        transaccion.executeWithoutResult(estado -> {
            jdbc.execute("set local session_replication_role = replica");
            Long usuarioDesde = ultimoId.get("usuarios");
            String nuevos = "select id::text from usuarios where id > ?";
            jdbc.update("""
                    delete from spring_session_attributes where session_primary_id in
                        (select primary_id from spring_session where principal_name in (%s))
                    """.formatted(nuevos), usuarioDesde);
            jdbc.update("delete from spring_session where principal_name in (%s)".formatted(nuevos), usuarioDesde);
            for (String tabla : TABLAS) {
                jdbc.update("delete from " + tabla + " where id > ?", ultimoId.get(tabla));
            }
        });
    }

    @Test
    void siDosOperacionesSacanElUltimoArticuloALaVezUnaFuncionaYLaOtraFallaCon409() throws Exception {
        Articulo maceta = datos.articulo(local, TipoArticulo.reventa, "Maceta", categoria);
        datos.stock(maceta, principal, "1");
        Long usuario = datos.usuario("Vendedor").getId();
        RequestPostProcessor dueno = datos.en(local, Perfil.dueno_local);
        CountDownLatch primeraTieneElBloqueo = new CountDownLatch(1);
        CountDownLatch soltar = new CountDownLatch(1);

        // la primera saca el último artículo y mantiene abierta su transacción
        Future<?> primera = hilos.submit(() -> transaccion.executeWithoutResult(estado -> {
            registro.registrar(TipoMovimientoStock.ajuste,
                    List.of(new RegistroStock.Renglon(maceta, principal, BigDecimal.ONE.negate())), "Venta A", null,
                    usuario);
            primeraTieneElBloqueo.countDown();
            esperar(soltar);
        }));
        assertThat(primeraTieneElBloqueo.await(10, TimeUnit.SECONDS)).isTrue();

        // la segunda intenta lo mismo por la API y queda esperando el bloqueo de la fila
        Future<MockHttpServletResponse> segunda = hilos.submit(ajuste(dueno, "Venta B", maceta, "-1"));
        assertThatThrownBy(() -> segunda.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);

        soltar.countDown();
        primera.get(10, TimeUnit.SECONDS);
        MockHttpServletResponse respuesta = segunda.get(10, TimeUnit.SECONDS);
        assertThat(respuesta.getStatus()).isEqualTo(409);
        assertThat(mensaje(respuesta)).startsWith("Stock insuficiente");

        assertThat(datos.cantidad(maceta, principal)).isEqualByComparingTo("0");
        assertThat(movimientos(maceta)).isEqualTo(2);
    }

    @Test
    void dosAjustesSimultaneosPorLaApiSobreElUltimoArticulo() throws Exception {
        Articulo maceta = datos.articulo(local, TipoArticulo.reventa, "Maceta", categoria);
        datos.stock(maceta, principal, "1");
        RequestPostProcessor duenoA = datos.en(local, Perfil.dueno_local);
        RequestPostProcessor duenoB = datos.en(local, Perfil.dueno_local);
        CyclicBarrier largada = new CyclicBarrier(2);

        Future<MockHttpServletResponse> a = hilos.submit(enLaLargada(largada, ajuste(duenoA, "Venta A", maceta, "-1")));
        Future<MockHttpServletResponse> b = hilos.submit(enLaLargada(largada, ajuste(duenoB, "Venta B", maceta, "-1")));

        List<MockHttpServletResponse> respuestas = List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
        assertThat(respuestas).extracting(MockHttpServletResponse::getStatus).containsExactlyInAnyOrder(201, 409);
        assertThat(respuestas.stream().filter(r -> r.getStatus() == 409).findFirst().map(this::mensaje).orElseThrow())
                .startsWith("Stock insuficiente");
        assertThat(datos.cantidad(maceta, principal)).isEqualByComparingTo("0");
        assertThat(movimientos(maceta)).isEqualTo(2);
    }

    @Test
    void unAjusteQueFallaEnUnRenglonRevierteLosQueYaSeHabianRegistrado() throws Exception {
        // la maceta se crea primero: tiene el id menor y su renglón se registra antes que el que falla
        Articulo maceta = datos.articulo(local, TipoArticulo.reventa, "Maceta", categoria);
        Articulo regadera = datos.articulo(local, TipoArticulo.reventa, "Regadera", categoria);
        datos.stock(regadera, principal, "1");
        RequestPostProcessor dueno = datos.en(local, Perfil.dueno_local);

        MockHttpServletResponse respuesta = mvc.perform(json(post("/api/stock/ajustes"), dueno, """
                        {"motivo": "Recuento", "renglones": [
                            {"articuloId": %d, "ubicacionId": %d, "cantidad": -3},
                            {"articuloId": %d, "ubicacionId": %d, "cantidad": 5}]}
                        """.formatted(regadera.getId(), principal.getId(), maceta.getId(), principal.getId())))
                .andReturn().getResponse();

        assertThat(respuesta.getStatus()).isEqualTo(409);
        assertThat(mensaje(respuesta)).startsWith("Stock insuficiente");
        assertThat(movimientos(maceta)).isZero();
        assertThat(datos.cantidad(maceta, principal)).isEqualByComparingTo("0");
        assertThat(datos.cantidad(regadera, principal)).isEqualByComparingTo("1");
        assertThat(jdbc.queryForObject("select count(*) from auditorias where accion = 'ajustar_stock' and id > ?",
                Integer.class, ultimoId.get("auditorias"))).isZero();
    }

    private Callable<MockHttpServletResponse> ajuste(RequestPostProcessor sesion, String motivo, Articulo articulo,
            String cantidad) {
        return () -> mvc.perform(json(post("/api/stock/ajustes"), sesion, """
                {"motivo": "%s", "renglones": [{"articuloId": %d, "ubicacionId": %d, "cantidad": %s}]}
                """.formatted(motivo, articulo.getId(), principal.getId(), cantidad))).andReturn().getResponse();
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

    private String mensaje(MockHttpServletResponse respuesta) {
        try {
            return mapper.readTree(respuesta.getContentAsString()).get("mensaje").asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private int movimientos(Articulo articulo) {
        return jdbc.queryForObject("select count(*) from movimientos_stock where articulo_id = ?", Integer.class,
                articulo.getId());
    }
}
