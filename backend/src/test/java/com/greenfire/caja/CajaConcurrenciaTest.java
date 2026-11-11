package com.greenfire.caja;

import static com.greenfire.Datos.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.greenfire.Datos;
import com.greenfire.DatosConfirmados;
import com.greenfire.PruebaIntegracion;
import com.greenfire.ambitos.Ambito;
import com.greenfire.seguridad.Perfil;

/**
 * Caja con transacciones reales (NOT_SUPPORTED): aperturas simultáneas, un cierre que falla a mitad de camino y un
 * cierre al mismo tiempo que un movimiento, que se ordenan por el bloqueo de la sesión. Lo que queda guardado lo
 * borra {@link DatosConfirmados}.
 */
@PruebaIntegracion
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class CajaConcurrenciaTest {

    private static final String GASTO =
            "{\"rubro\":\"gasto\",\"medioPago\":\"efectivo\",\"monto\":30,\"observacion\":\"Concurrente\"}";
    private static final String CIERRE = "{\"efectivoContado\": 100}";

    @Autowired
    MockMvc mvc;
    @Autowired
    Datos datos;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TransactionTemplate transaccion;

    ExecutorService hilos = Executors.newFixedThreadPool(6);
    DatosConfirmados confirmados;
    Ambito local;
    RequestPostProcessor empleado;
    RequestPostProcessor dueno;

    @BeforeEach
    void datos() {
        confirmados = new DatosConfirmados(jdbc, transaccion);
        local = datos.local("Concurrencia");
        empleado = datos.en(local, Perfil.empleado_local);
        dueno = datos.en(local, Perfil.dueno_local);
    }

    @AfterEach
    void limpiar() {
        hilos.shutdownNow();
        confirmados.limpiar();
    }

    @Test
    void deVariasAperturasSimultaneasUnaRespondeCreadaYLasDemasConflicto() throws Exception {
        int simultaneas = 5;
        CyclicBarrier largada = new CyclicBarrier(simultaneas);
        List<Future<MvcResult>> aperturas = new ArrayList<>();
        for (int i = 0; i < simultaneas; i++) {
            RequestPostProcessor quien = i % 2 == 0 ? empleado : dueno;
            aperturas.add(hilos.submit(() -> {
                largada.await(10, TimeUnit.SECONDS);
                return pedir(quien, "/api/caja/abrir", "{\"saldoInicial\": 100}");
            }));
        }

        List<Integer> estados = new ArrayList<>();
        for (Future<MvcResult> f : aperturas) {
            MvcResult r = f.get(20, TimeUnit.SECONDS);
            estados.add(r.getResponse().getStatus());
            if (r.getResponse().getStatus() == 409) {
                assertThat(r.getResponse().getContentAsString()).contains("Ya hay una caja abierta en este local.");
            }
        }
        assertThat(estados).containsOnlyOnce(201).containsOnly(201, 409);
        assertThat(jdbc.queryForObject("select count(*) from sesiones_caja where ambito_id = ?", Integer.class,
                local.getId())).isEqualTo(1);
        assertThat(auditorias("abrir")).isEqualTo(1);
    }

    @Test
    void unCierreQueFallaAMitadDeCaminoDejaLaCajaAbierta() throws Exception {
        long id = abrir();
        assertThat(pedir(empleado, "/api/caja/movimientos", GASTO).getResponse().getStatus()).isEqualTo(201);

        // falla el registro de auditoría del cierre, después de guardar los totales
        jdbc.execute("alter table auditorias add constraint prueba_falla check (accion <> 'cerrar') not valid");
        try {
            MvcResult r = pedir(empleado, "/api/caja/cerrar", CIERRE);
            assertThat(r.getResponse().getStatus()).isEqualTo(500);
        } finally {
            jdbc.execute("alter table auditorias drop constraint prueba_falla");
        }

        Map<String, Object> fila = jdbc.queryForMap("select * from sesiones_caja where id = ?", id);
        assertThat(fila).containsEntry("estado", "abierta").containsEntry("cerrada_at", null)
                .containsEntry("efectivo_esperado", null).containsEntry("efectivo_contado", null)
                .containsEntry("total_efectivo", null).containsEntry("usuario_cierre_id", null);
        assertThat(auditorias("cerrar")).isZero();

        // sigue abierta: admite movimientos y se cierra bien
        assertThat(pedir(empleado, "/api/caja/movimientos", GASTO).getResponse().getStatus()).isEqualTo(201);
        assertThat(pedir(empleado, "/api/caja/cerrar", CIERRE).getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select efectivo_esperado from sesiones_caja where id = ?", BigDecimal.class, id))
                .isEqualByComparingTo("40");
    }

    @Test
    void unMovimientoQueLlegaDuranteElCierreEsperaYEncuentraLaCajaCerrada() throws Exception {
        long id = abrir();
        CountDownLatch soltar = new CountDownLatch(1);
        Future<?> bloqueo = bloquearSesion(id, soltar);

        Future<MvcResult> cierre = hilos.submit(() -> pedir(empleado, "/api/caja/cerrar", CIERRE));
        esperarBloqueados(1);
        Future<MvcResult> gasto = hilos.submit(() -> pedir(empleado, "/api/caja/movimientos", GASTO));
        esperarBloqueados(2);
        soltar.countDown();
        bloqueo.get(10, TimeUnit.SECONDS);

        assertThat(cierre.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
        MvcResult r = gasto.get(10, TimeUnit.SECONDS);
        assertThat(r.getResponse().getStatus()).isEqualTo(409);
        assertThat(r.getResponse().getContentAsString()).contains("No hay una caja abierta en este local.");
        assertThat(movimientos(id)).isZero();
        assertThat(jdbc.queryForObject("select efectivo_esperado from sesiones_caja where id = ?", BigDecimal.class, id))
                .isEqualByComparingTo("100");
    }

    @Test
    void unCierreQueLlegaDuranteUnMovimientoEsperaYLoIncluye() throws Exception {
        long id = abrir();
        CountDownLatch soltar = new CountDownLatch(1);
        Future<?> bloqueo = bloquearSesion(id, soltar);

        Future<MvcResult> gasto = hilos.submit(() -> pedir(empleado, "/api/caja/movimientos", GASTO));
        esperarBloqueados(1);
        Future<MvcResult> cierre = hilos.submit(() -> pedir(dueno, "/api/caja/cerrar", CIERRE));
        esperarBloqueados(2);
        soltar.countDown();
        bloqueo.get(10, TimeUnit.SECONDS);

        assertThat(gasto.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(201);
        assertThat(cierre.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
        assertThat(movimientos(id)).isEqualTo(1);
        assertThat(jdbc.queryForMap("select egresos_efectivo, efectivo_esperado, diferencia from sesiones_caja where id = ?", id))
                .containsEntry("egresos_efectivo", new BigDecimal("30.00"))
                .containsEntry("efectivo_esperado", new BigDecimal("70.00"))
                .containsEntry("diferencia", new BigDecimal("30.00"));
    }

    @Test
    void cierreYMovimientoALaVezDanUnResultadoCoherente() throws Exception {
        for (int vuelta = 0; vuelta < 5; vuelta++) {
            long id = abrir();
            CyclicBarrier largada = new CyclicBarrier(2);
            Future<MvcResult> gasto = hilos.submit(() -> {
                largada.await(10, TimeUnit.SECONDS);
                return pedir(empleado, "/api/caja/movimientos", GASTO);
            });
            Future<MvcResult> cierre = hilos.submit(() -> {
                largada.await(10, TimeUnit.SECONDS);
                return pedir(dueno, "/api/caja/cerrar", CIERRE);
            });

            assertThat(cierre.get(20, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(200);
            int estadoGasto = gasto.get(20, TimeUnit.SECONDS).getResponse().getStatus();
            assertThat(estadoGasto).isIn(201, 409);
            // o el gasto entró antes del cierre y el cierre lo cuenta, o llegó tarde y no quedó
            BigDecimal egresos = jdbc.queryForObject("select egresos_efectivo from sesiones_caja where id = ?",
                    BigDecimal.class, id);
            assertThat(movimientos(id)).isEqualTo(estadoGasto == 201 ? 1 : 0);
            assertThat(egresos).isEqualByComparingTo(estadoGasto == 201 ? "30" : "0");
        }
    }

    // --- Ayudas ---

    private long abrir() throws Exception {
        MvcResult r = pedir(empleado, "/api/caja/abrir", "{\"saldoInicial\": 100}");
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        return jdbc.queryForObject("select id from sesiones_caja where ambito_id = ? and estado = 'abierta'",
                Long.class, local.getId());
    }

    private MvcResult pedir(RequestPostProcessor sesion, String ruta, String cuerpo) throws Exception {
        return mvc.perform(json(post(ruta), sesion, cuerpo)).andReturn();
    }

    /** Toma el bloqueo de la sesión en otra transacción y lo suelta con la señal, como un cierre en curso. */
    private Future<?> bloquearSesion(long id, CountDownLatch soltar) throws Exception {
        CountDownLatch tomado = new CountDownLatch(1);
        Future<?> f = hilos.submit(() -> transaccion.executeWithoutResult(estado -> {
            jdbc.queryForObject("select id from sesiones_caja where id = ? for update", Long.class, id);
            tomado.countDown();
            try {
                soltar.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        assertThat(tomado.await(10, TimeUnit.SECONDS)).isTrue();
        return f;
    }

    /** Espera a que haya esa cantidad de conexiones de la base esperando un bloqueo. */
    private void esperarBloqueados(int cantidad) throws Exception {
        Callable<Integer> bloqueados = () -> jdbc.queryForObject("""
                select count(*) from pg_stat_activity
                where datname = current_database() and wait_event_type = 'Lock'
                """, Integer.class);
        long limite = System.currentTimeMillis() + 10_000;
        while (bloqueados.call() < cantidad) {
            assertThat(System.currentTimeMillis()).as("esperando %d pedidos bloqueados", cantidad).isLessThan(limite);
            Thread.sleep(20);
        }
    }

    private int movimientos(long sesion) {
        return jdbc.queryForObject("select count(*) from movimientos_caja where sesion_caja_id = ?", Integer.class,
                sesion);
    }

    private int auditorias(String accion) {
        return jdbc.queryForObject("select count(*) from auditorias where id > ? and accion = ? and entidad = 'sesion_caja'",
                Integer.class, confirmados.ultimoId("auditorias"), accion);
    }
}
