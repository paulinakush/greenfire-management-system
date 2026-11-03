package com.greenfire.stock;

import static com.greenfire.Datos.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.math.BigDecimal;
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
import com.greenfire.seguridad.Perfil;

/**
 * Cierres de conteo con transacciones reales, que se confirman o se revierten de verdad (NOT_SUPPORTED). Lo que
 * dejan guardado lo borra {@link DatosConfirmados}.
 */
@PruebaIntegracion
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ConteoConcurrenciaTest {

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
    DatosConfirmados confirmados;
    Ambito local;
    Categoria categoria;
    UbicacionStock principal;
    RequestPostProcessor dueno;

    @BeforeEach
    void datos() {
        confirmados = new DatosConfirmados(jdbc, transaccion);
        local = datos.local("Conteos");
        categoria = datos.categoria("Macetas");
        principal = datos.principal(local);
        dueno = datos.en(local, Perfil.dueno_local);
    }

    @AfterEach
    void limpiar() {
        hilos.shutdownNow();
        confirmados.limpiar();
    }

    @Test
    void unCierreQueFallaAMitadDeCaminoNoAplicaNada() throws Exception {
        Articulo maceta = datos.articulo(local, TipoArticulo.reventa, "Maceta", categoria);
        Articulo regadera = datos.articulo(local, TipoArticulo.reventa, "Regadera", categoria);
        Articulo pala = datos.articulo(local, TipoArticulo.reventa, "Pala", categoria);
        datos.stock(maceta, principal, "10");
        datos.stock(regadera, principal, "5");
        long id = abrir();
        cargar(id, maceta, "8");
        cargar(id, regadera, "9");
        cargar(id, pala, "4");

        // falla el último paso del cierre, la auditoría: para entonces ya se registraron los movimientos, se tomó la
        // cantidad del sistema y el conteo pasó a cerrado
        jdbc.execute("""
                alter table auditorias add constraint prueba_falla
                    check (accion <> 'cerrar' or entidad <> 'conteo_stock') not valid
                """);
        MockHttpServletResponse respuesta;
        try {
            respuesta = mvc.perform(post("/api/conteos/" + id + "/cerrar").with(dueno)).andReturn().getResponse();
        } finally {
            jdbc.execute("alter table auditorias drop constraint prueba_falla");
        }

        assertThat(respuesta.getStatus()).isEqualTo(500);
        assertThat(jdbc.queryForObject("select estado from conteos_stock where id = ?", String.class, id))
                .isEqualTo("abierto");
        assertThat(jdbc.queryForObject("select count(*) from detalle_conteo where conteo_id = ? and cantidad_sistema is not null",
                Integer.class, id)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from movimientos_stock where conteo_id = ?", Integer.class, id))
                .isZero();
        assertThat(datos.cantidad(maceta, principal)).isEqualByComparingTo("10");
        assertThat(datos.cantidad(regadera, principal)).isEqualByComparingTo("5");
        assertThat(jdbc.queryForObject("select count(*) from stock_articulos where articulo_id = ?", Integer.class,
                pala.getId())).isZero();

        // sin la falla, el mismo conteo se cierra
        assertThat(mvc.perform(post("/api/conteos/" + id + "/cerrar").with(dueno)).andReturn().getResponse()
                .getStatus()).isEqualTo(200);
        assertThat(datos.cantidad(maceta, principal)).isEqualByComparingTo("8");
        assertThat(datos.cantidad(regadera, principal)).isEqualByComparingTo("9");
        assertThat(datos.cantidad(pala, principal)).isEqualByComparingTo("4");
    }

    @Test
    void unCierreSimultaneoConUnAjusteTomaLaCantidadDespuesDelAjuste() throws Exception {
        Articulo maceta = datos.articulo(local, TipoArticulo.reventa, "Maceta", categoria);
        datos.stock(maceta, principal, "10");
        Long usuario = datos.usuario("Vendedor").getId();
        long id = abrir();
        cargar(id, maceta, "7");
        CountDownLatch ajusteTieneElBloqueo = new CountDownLatch(1);
        CountDownLatch soltar = new CountDownLatch(1);

        // un ajuste saca 2 y mantiene abierta su transacción
        Future<?> ajuste = hilos.submit(() -> transaccion.executeWithoutResult(estado -> {
            registro.registrar(TipoMovimientoStock.ajuste,
                    List.of(new RegistroStock.Renglon(maceta, principal, new BigDecimal("-2"))), "Rotura", null,
                    usuario);
            ajusteTieneElBloqueo.countDown();
            esperar(soltar);
        }));
        assertThat(ajusteTieneElBloqueo.await(10, TimeUnit.SECONDS)).isTrue();

        // el cierre espera el bloqueo de la fila de stock
        Future<MockHttpServletResponse> cierre = hilos.submit(
                () -> mvc.perform(post("/api/conteos/" + id + "/cerrar").with(dueno)).andReturn().getResponse());
        assertThatThrownBy(() -> cierre.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);

        soltar.countDown();
        ajuste.get(10, TimeUnit.SECONDS);
        MockHttpServletResponse respuesta = cierre.get(10, TimeUnit.SECONDS);
        assertThat(respuesta.getStatus()).isEqualTo(200);

        // el sistema tenía 8 al cerrar (10 − 2 del ajuste), se contaron 7: diferencia −1 y queda lo contado
        var item = mapper.readTree(respuesta.getContentAsString()).get("items").get(0);
        assertThat(item.get("cantidadSistema").decimalValue()).isEqualByComparingTo("8");
        assertThat(item.get("diferencia").decimalValue()).isEqualByComparingTo("-1");
        assertThat(datos.cantidad(maceta, principal)).isEqualByComparingTo("7");
        assertThat(jdbc.queryForList("select cantidad from movimientos_stock where articulo_id = ? order by id",
                BigDecimal.class, maceta.getId())).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("10"), new BigDecimal("-2"), new BigDecimal("-1"));
    }

    @Test
    void dosAperturasSimultaneasEnLaMismaUbicacionDejanUnSoloConteoAbierto() throws Exception {
        RequestPostProcessor otroDueno = datos.en(local, Perfil.dueno_local);
        CyclicBarrier largada = new CyclicBarrier(2);

        Future<MockHttpServletResponse> a = hilos.submit(enLaLargada(largada, apertura(dueno)));
        Future<MockHttpServletResponse> b = hilos.submit(enLaLargada(largada, apertura(otroDueno)));

        assertThat(List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS)))
                .extracting(MockHttpServletResponse::getStatus).containsExactlyInAnyOrder(201, 409);
        assertThat(jdbc.queryForObject("select count(*) from conteos_stock where ubicacion_stock_id = ? and estado = 'abierto'",
                Integer.class, principal.getId())).isEqualTo(1);
    }

    private long abrir() throws Exception {
        MockHttpServletResponse r = apertura(dueno).call();
        assertThat(r.getStatus()).isEqualTo(201);
        return mapper.readTree(r.getContentAsString()).get("id").asLong();
    }

    private Callable<MockHttpServletResponse> apertura(RequestPostProcessor sesion) {
        return () -> mvc.perform(json(post("/api/conteos"), sesion,
                "{\"ubicacionId\": %d}".formatted(principal.getId()))).andReturn().getResponse();
    }

    private void cargar(long id, Articulo articulo, String cantidad) throws Exception {
        assertThat(mvc.perform(json(put("/api/conteos/" + id + "/items"), dueno,
                        "{\"items\": [{\"articuloId\": %d, \"cantidadContada\": %s}]}".formatted(articulo.getId(), cantidad)))
                .andReturn().getResponse().getStatus()).isEqualTo(200);
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
