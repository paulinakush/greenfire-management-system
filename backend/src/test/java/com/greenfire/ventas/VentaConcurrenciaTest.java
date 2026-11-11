package com.greenfire.ventas;

import static com.greenfire.Datos.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.greenfire.Datos;
import com.greenfire.DatosConfirmados;
import com.greenfire.PruebaIntegracion;
import com.greenfire.ambitos.Ambito;
import com.greenfire.catalogo.Articulo;
import com.greenfire.catalogo.TipoArticulo;
import com.greenfire.precios.ListaPrecios;
import com.greenfire.seguridad.Perfil;
import com.greenfire.stock.UbicacionStock;

/**
 * Ventas con transacciones reales (NOT_SUPPORTED): dos ventas del último artículo, una venta que falla a mitad de
 * camino (no queda nada, ni el número del comprobante) y la anulación contra la devolución de la misma venta, que se
 * ordenan por el bloqueo de la venta. Lo que queda guardado lo borra {@link DatosConfirmados}.
 */
@PruebaIntegracion
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class VentaConcurrenciaTest {

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

    ExecutorService hilos = Executors.newFixedThreadPool(4);
    DatosConfirmados confirmados;
    Ambito local;
    Articulo maceta;
    UbicacionStock principal;
    RequestPostProcessor empleado;
    RequestPostProcessor dueno;
    String unaMaceta;

    @BeforeEach
    void datos() throws Exception {
        confirmados = new DatosConfirmados(jdbc, transaccion);
        local = datos.local("Concurrencia");
        datos.puntoVentaX(local);
        maceta = datos.articulo(local, TipoArticulo.reventa, "Maceta", datos.categoria("Macetas"));
        ListaPrecios lista = datos.lista(local, "Mostrador", true, true);
        datos.precio(lista, maceta, "1000");
        principal = datos.principal(local);
        empleado = datos.en(local, Perfil.empleado_local);
        dueno = datos.en(local, Perfil.dueno_local);
        assertThat(pedir(empleado, "/api/caja/abrir", "{\"saldoInicial\": 0}").getResponse().getStatus()).isEqualTo(201);
        unaMaceta = venta("1");
    }

    @AfterEach
    void limpiar() {
        hilos.shutdownNow();
        confirmados.limpiar();
    }

    @Test
    void deDosVentasDelUltimoArticuloUnaGanaYLaOtraRecibe409() throws Exception {
        for (int vuelta = 1; vuelta <= 3; vuelta++) {
            datos.stock(maceta, principal, "1");
            CyclicBarrier largada = new CyclicBarrier(2);
            List<Future<MvcResult>> ventas = new ArrayList<>();
            for (RequestPostProcessor quien : List.of(empleado, dueno)) {
                ventas.add(hilos.submit(() -> {
                    largada.await(10, TimeUnit.SECONDS);
                    return pedir(quien, "/api/ventas", unaMaceta);
                }));
            }
            List<Integer> estados = new ArrayList<>();
            for (Future<MvcResult> f : ventas) {
                MvcResult r = f.get(20, TimeUnit.SECONDS);
                estados.add(r.getResponse().getStatus());
                if (r.getResponse().getStatus() == 409) {
                    assertThat(r.getResponse().getContentAsString()).contains("Stock insuficiente");
                }
            }
            assertThat(estados).containsExactlyInAnyOrder(201, 409);
            assertThat(datos.cantidad(maceta, principal)).isEqualByComparingTo("0");
            assertThat(cuenta("select count(*) from ventas where ambito_id = ?")).isEqualTo(vuelta);
            assertThat(cuenta("select count(*) from comprobantes c join puntos_venta p on p.id = c.punto_venta_id where p.ambito_id = ?"))
                    .isEqualTo(vuelta);
            assertThat(ultimoNumero("comprobante_venta")).isEqualTo(vuelta);
            assertThat(cuenta("""
                    select count(*) from movimientos_caja m join sesiones_caja s on s.id = m.sesion_caja_id
                    where s.ambito_id = ? and m.origen = 'venta'
                    """)).isEqualTo(vuelta);
        }
    }

    @Test
    void unaVentaQueFallaAMitadDeCaminoNoDejaNadaNiConsumeElNumero() throws Exception {
        datos.stock(maceta, principal, "5");
        // falla el registro de auditoría de la venta: ya se emitió el comprobante y se movió el stock
        jdbc.execute("alter table auditorias add constraint prueba_falla check (entidad <> 'venta') not valid");
        try {
            assertThat(pedir(empleado, "/api/ventas", venta("2")).getResponse().getStatus()).isEqualTo(500);
        } finally {
            jdbc.execute("alter table auditorias drop constraint prueba_falla");
        }

        assertThat(cuenta("select count(*) from ventas where ambito_id = ?")).isZero();
        assertThat(cuenta("select count(*) from comprobantes c join puntos_venta p on p.id = c.punto_venta_id where p.ambito_id = ?"))
                .isZero();
        assertThat(ultimoNumero("comprobante_venta")).isZero();
        assertThat(datos.cantidad(maceta, principal)).isEqualByComparingTo("5");
        assertThat(jdbc.queryForObject("select count(*) from movimientos_stock where articulo_id = ? and tipo = 'venta'",
                Integer.class, maceta.getId())).isZero();
        assertThat(cuenta("""
                select count(*) from movimientos_caja m join sesiones_caja s on s.id = m.sesion_caja_id where s.ambito_id = ?
                """)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from auditorias where id > ? and entidad in ('comprobante', 'movimiento_caja')",
                Integer.class, confirmados.ultimoId("auditorias"))).isZero();

        // la próxima venta toma el número 1
        MvcResult r = pedir(empleado, "/api/ventas", venta("2"));
        assertThat(r.getResponse().getStatus()).isEqualTo(201);
        assertThat(mapper.readTree(r.getResponse().getContentAsString()).at("/comprobante/numero").asText())
                .isEqualTo("00001-00000001");
        assertThat(datos.cantidad(maceta, principal)).isEqualByComparingTo("3");
    }

    @Test
    void laAnulacionYLaDevolucionDeLaMismaVentaSeOrdenanPorElBloqueoDeLaVenta() throws Exception {
        for (int vuelta = 0; vuelta < 4; vuelta++) {
            datos.stock(maceta, principal, "2");
            long id = vender("2");
            CyclicBarrier largada = new CyclicBarrier(2);
            Future<MvcResult> anulacion = hilos.submit(() -> {
                largada.await(10, TimeUnit.SECONDS);
                return pedir(dueno, "/api/ventas/" + id + "/anular", "{\"motivo\": \"Anulada\"}");
            });
            Future<MvcResult> devolucion = hilos.submit(() -> {
                largada.await(10, TimeUnit.SECONDS);
                return pedir(dueno, "/api/ventas/" + id + "/devoluciones", devolverTodo(id));
            });
            int anulo = anulacion.get(20, TimeUnit.SECONDS).getResponse().getStatus();
            int devolvio = devolucion.get(20, TimeUnit.SECONDS).getResponse().getStatus();

            // gana una sola: el stock vuelve una vez y el dinero sale una vez
            assertThat(List.of(anulo, devolvio)).isIn(List.of(200, 409), List.of(409, 201));
            assertThat(datos.cantidad(maceta, principal)).isEqualByComparingTo("2");
            String estado = jdbc.queryForObject("select estado from ventas where id = ?", String.class, id);
            int devoluciones = jdbc.queryForObject("select count(*) from devoluciones_venta where venta_id = ?",
                    Integer.class, id);
            assertThat(estado).isEqualTo(anulo == 200 ? "anulada" : "confirmada");
            assertThat(devoluciones).isEqualTo(devolvio == 201 ? 1 : 0);
            assertThat(jdbc.queryForObject("select coalesce(sum(monto), 0) from movimientos_caja where venta_id = ? and origen = 'devolucion'",
                    BigDecimal.class, id)).isEqualByComparingTo("2000");
            datos.stock(maceta, principal, "-2");
        }
    }

    @Test
    void laDevolucionQueLlegaPrimeroGanaYLaAnulacionQueEsperaEncuentraLaDevolucion() throws Exception {
        datos.stock(maceta, principal, "2");
        long id = vender("2");
        CountDownLatch soltar = new CountDownLatch(1);
        Future<?> bloqueo = bloquearVenta(id, soltar);

        Future<MvcResult> devolucion = hilos.submit(() -> pedir(dueno, "/api/ventas/" + id + "/devoluciones", devolverTodo(id)));
        esperarBloqueados(1);
        Future<MvcResult> anulacion = hilos.submit(() -> pedir(dueno, "/api/ventas/" + id + "/anular", "{\"motivo\": \"x\"}"));
        esperarBloqueados(2);
        soltar.countDown();
        bloqueo.get(10, TimeUnit.SECONDS);

        assertThat(devolucion.get(10, TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(201);
        MvcResult r = anulacion.get(10, TimeUnit.SECONDS);
        assertThat(r.getResponse().getStatus()).isEqualTo(409);
        assertThat(r.getResponse().getContentAsString()).contains("tiene devoluciones");
        assertThat(datos.cantidad(maceta, principal)).isEqualByComparingTo("2");
    }

    @Test
    void dosDevolucionesSimultaneasNoDevuelvenMasDeLoVendido() throws Exception {
        datos.stock(maceta, principal, "2");
        long id = vender("2");
        CyclicBarrier largada = new CyclicBarrier(2);
        List<Future<MvcResult>> devoluciones = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            devoluciones.add(hilos.submit(() -> {
                largada.await(10, TimeUnit.SECONDS);
                return pedir(dueno, "/api/ventas/" + id + "/devoluciones", devolverTodo(id));
            }));
        }
        List<Integer> estados = new ArrayList<>();
        for (Future<MvcResult> f : devoluciones) {
            estados.add(f.get(20, TimeUnit.SECONDS).getResponse().getStatus());
        }
        assertThat(estados).containsExactlyInAnyOrder(201, 409);
        assertThat(jdbc.queryForObject("""
                select sum(d.cantidad) from detalle_devoluciones_venta d join devoluciones_venta v on v.id = d.devolucion_id
                where v.venta_id = ?
                """, BigDecimal.class, id)).isEqualByComparingTo("2");
        assertThat(datos.cantidad(maceta, principal)).isEqualByComparingTo("2");
    }

    // --- Ayudas ---

    private String venta(String cantidad) {
        return "{\"medioPago\": \"efectivo\", \"renglones\": [{\"articuloId\": %d, \"cantidad\": %s}]}"
                .formatted(maceta.getId(), cantidad);
    }

    private long vender(String cantidad) throws Exception {
        MvcResult r = pedir(empleado, "/api/ventas", venta(cantidad));
        assertThat(r.getResponse().getStatus()).as(r.getResponse().getContentAsString()).isEqualTo(201);
        return mapper.readTree(r.getResponse().getContentAsString()).get("id").asLong();
    }

    private String devolverTodo(long venta) {
        long renglon = jdbc.queryForObject("select id from detalle_ventas where venta_id = ?", Long.class, venta);
        return "{\"motivo\": \"Devolución\", \"renglones\": [{\"detalleVentaId\": %d, \"cantidad\": 2}]}".formatted(renglon);
    }

    private MvcResult pedir(RequestPostProcessor sesion, String ruta, String cuerpo) throws Exception {
        return mvc.perform(json(post(ruta), sesion, cuerpo)).andReturn();
    }

    private int cuenta(String sql) {
        return jdbc.queryForObject(sql, Integer.class, local.getId());
    }

    private int ultimoNumero(String tipo) {
        return jdbc.queryForObject("""
                select pvc.ultimo_numero from punto_venta_comprobantes pvc join puntos_venta p on p.id = pvc.punto_venta_id
                where p.ambito_id = ? and pvc.tipo = ? and pvc.letra = 'X'
                """, Integer.class, local.getId(), tipo);
    }

    /** Toma el bloqueo de la venta en otra transacción y lo suelta con la señal. */
    private Future<?> bloquearVenta(long id, CountDownLatch soltar) throws Exception {
        CountDownLatch tomado = new CountDownLatch(1);
        Future<?> f = hilos.submit(() -> transaccion.executeWithoutResult(estado -> {
            jdbc.queryForObject("select id from ventas where id = ? for update", Long.class, id);
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
        long limite = System.currentTimeMillis() + 10_000;
        while (jdbc.queryForObject("""
                select count(*) from pg_stat_activity where datname = current_database() and wait_event_type = 'Lock'
                """, Integer.class) < cantidad) {
            assertThat(System.currentTimeMillis()).as("esperando %d pedidos bloqueados", cantidad).isLessThan(limite);
            Thread.sleep(20);
        }
    }
}
