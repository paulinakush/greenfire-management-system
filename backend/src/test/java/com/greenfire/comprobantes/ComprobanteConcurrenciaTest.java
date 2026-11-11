package com.greenfire.comprobantes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.greenfire.Datos;
import com.greenfire.DatosConfirmados;
import com.greenfire.PruebaIntegracion;
import com.greenfire.ambitos.Ambito;
import com.greenfire.ambitos.AmbitoRepository;
import com.greenfire.catalogo.Articulo;
import com.greenfire.catalogo.ArticuloRepository;
import com.greenfire.catalogo.TipoArticulo;
import com.greenfire.comprobantes.EmisorComprobantes.Emision;
import com.greenfire.comprobantes.EmisorComprobantes.Renglon;
import com.greenfire.fiscal.LetraComprobante;
import com.greenfire.fiscal.PuntoVenta;
import com.greenfire.fiscal.TipoComprobante;

/**
 * Emisiones con transacciones reales, que se confirman o se revierten de verdad (NOT_SUPPORTED): varias a la vez
 * en el mismo punto de venta, y emisiones que fallan a mitad de camino. Al confirmar corren además las
 * verificaciones diferidas de V12 (número usado y total). Lo que queda guardado lo borra {@link DatosConfirmados}.
 */
@PruebaIntegracion
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ComprobanteConcurrenciaTest {

    private static final int SIMULTANEAS = 8;

    @Autowired
    EmisorComprobantes emisor;
    @Autowired
    NumeradorComprobantes numerador;
    @Autowired
    Datos datos;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    TransactionTemplate transaccion;
    @Autowired
    AmbitoRepository ambitos;
    @Autowired
    ArticuloRepository articulos;

    ExecutorService hilos = Executors.newFixedThreadPool(SIMULTANEAS);
    DatosConfirmados confirmados;
    Ambito local;
    Articulo maceta;
    PuntoVenta pv;
    Long usuario;

    @BeforeEach
    void datos() {
        confirmados = new DatosConfirmados(jdbc, transaccion);
        local = datos.local("Concurrencia");
        maceta = datos.articulo(local, TipoArticulo.reventa, "Maceta", datos.categoria("Macetas"));
        pv = datos.puntoVentaX(local);
        usuario = datos.usuario("Caja").getId();
    }

    @AfterEach
    void limpiar() {
        hilos.shutdownNow();
        confirmados.limpiar();
    }

    @Test
    void variosComprobantesEmitidosALaVezRecibenNumerosSeguidosSinHuecosNiRepetidos() throws Exception {
        CyclicBarrier largada = new CyclicBarrier(SIMULTANEAS);
        List<Future<Integer>> emisiones = new ArrayList<>();
        for (int i = 0; i < SIMULTANEAS; i++) {
            emisiones.add(hilos.submit(() -> {
                largada.await(10, TimeUnit.SECONDS);
                return transaccion.execute(estado -> emitir().getNumero());
            }));
        }

        List<Integer> numeros = new ArrayList<>();
        for (Future<Integer> f : emisiones) {
            numeros.add(f.get(20, TimeUnit.SECONDS));
        }

        List<Integer> esperados = IntStream.rangeClosed(1, SIMULTANEAS).boxed().toList();
        assertThat(numeros).containsExactlyInAnyOrderElementsOf(esperados);
        assertThat(jdbc.queryForList("select numero from comprobantes where punto_venta_id = ? order by numero",
                Integer.class, pv.getId())).containsExactlyElementsOf(esperados);
        assertThat(ultimoNumero()).isEqualTo(SIMULTANEAS);
        // cada uno con su renglón y su registro de auditoría
        assertThat(jdbc.queryForObject("""
                select count(*) from detalle_comprobante d join comprobantes c on c.id = d.comprobante_id
                where c.punto_venta_id = ?
                """, Integer.class, pv.getId())).isEqualTo(SIMULTANEAS);
        assertThat(jdbc.queryForObject("select count(*) from auditorias where id > ? and entidad = 'comprobante'",
                Integer.class, confirmados.ultimoId("auditorias"))).isEqualTo(SIMULTANEAS);
    }

    @Test
    void unaOperacionQueFallaDespuesDeEmitirNoConsumeElNumero() {
        transaccion.executeWithoutResult(estado -> assertThat(emitir().getNumero()).isEqualTo(1));

        // la operación (por ejemplo, una venta) emite el comprobante y después falla: se revierte todo
        assertThatThrownBy(() -> transaccion.executeWithoutResult(estado -> {
            assertThat(emitir().getNumero()).isEqualTo(2);
            throw new IllegalStateException("Falló la operación después de emitir");
        })).hasMessage("Falló la operación después de emitir");

        assertThat(ultimoNumero()).isEqualTo(1);
        assertThat(cantidadDeComprobantes()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from auditorias where id > ? and entidad = 'comprobante'",
                Integer.class, confirmados.ultimoId("auditorias"))).isEqualTo(1);
        // el número 2 no se perdió: lo toma la próxima emisión
        assertThat(transaccion.<Integer>execute(estado -> emitir().getNumero())).isEqualTo(2);
    }

    @Test
    void unaEmisionQueLaBaseRechazaAlConfirmarNoConsumeElNumero() {
        // la emisión pasa, pero al confirmar falla la verificación diferida del total (un renglón de más)
        assertThatThrownBy(() -> transaccion.executeWithoutResult(estado -> {
            Comprobante c = emitir();
            jdbc.update("""
                    insert into detalle_comprobante (comprobante_id, descripcion, cantidad, precio_unitario,
                                                     alicuota_iva, subtotal)
                    values (?, 'De más', 1, 5, 0, 5)
                    """, c.getId());
        })).hasStackTraceContaining("no es la suma de sus renglones");

        assertThat(ultimoNumero()).isZero();
        assertThat(cantidadDeComprobantes()).isZero();
        assertThat(transaccion.<Integer>execute(estado -> emitir().getNumero())).isEqualTo(1);
    }

    @Test
    void laEmisionQueEsperaTomaElNumeroQueLiberaLaQueFalla() throws Exception {
        CountDownLatch primeraTieneElNumero = new CountDownLatch(1);
        CountDownLatch soltar = new CountDownLatch(1);

        Future<?> primera = hilos.submit(() -> transaccion.executeWithoutResult(estado -> {
            assertThat(emitir().getNumero()).isEqualTo(1);
            primeraTieneElNumero.countDown();
            esperar(soltar);
            throw new IllegalStateException("La primera falla con el número tomado");
        }));
        assertThat(primeraTieneElNumero.await(10, TimeUnit.SECONDS)).isTrue();

        // la segunda espera el bloqueo de la fila de numeración
        Future<Integer> segunda = hilos.submit(() -> transaccion.execute(estado -> emitir().getNumero()));
        assertThatThrownBy(() -> segunda.get(500, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);

        soltar.countDown();
        assertThatThrownBy(() -> primera.get(10, TimeUnit.SECONDS))
                .hasRootCauseMessage("La primera falla con el número tomado");
        assertThat(segunda.get(10, TimeUnit.SECONDS)).isEqualTo(1);
        assertThat(jdbc.queryForList("select numero from comprobantes where punto_venta_id = ?", Integer.class,
                pv.getId())).containsExactly(1);
        assertThat(ultimoNumero()).isEqualTo(1);
    }

    @Test
    void unComprobanteConfirmadoNoSumaNiCambiaRenglones() {
        long id = transaccion.execute(estado -> emitir().getId());

        assertThatThrownBy(() -> jdbc.update("""
                insert into detalle_comprobante (comprobante_id, descripcion, cantidad, precio_unitario,
                                                 alicuota_iva, subtotal)
                values (?, 'Agregado después', 1, 0, 0, 0)
                """, id)).hasStackTraceContaining("se carga al emitirlo");
        assertThatThrownBy(() -> jdbc.update("update detalle_comprobante set descripcion = 'x' where comprobante_id = ?",
                id)).hasStackTraceContaining("no se modifica ni se borra");
        assertThatThrownBy(() -> jdbc.update("delete from detalle_comprobante where comprobante_id = ?", id))
                .hasStackTraceContaining("no se modifica ni se borra");
        assertThatThrownBy(() -> jdbc.update("update comprobantes set total = 1 where id = ?", id))
                .hasStackTraceContaining("solo se anula");
        assertThatThrownBy(() -> jdbc.update("delete from comprobantes where id = ?", id))
                .hasStackTraceContaining("no se borran");
    }

    @Test
    void elEmisorYElNumeradorExigenUnaTransaccion() {
        Emision emision = new Emision(local, TipoComprobante.comprobante_venta, null, true,
                List.of(Renglon.de(maceta, BigDecimal.ONE, BigDecimal.TEN)), usuario);

        assertThatThrownBy(() -> emisor.emitir(emision)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> numerador.siguiente(local, TipoComprobante.recibo, LetraComprobante.X))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(ultimoNumero()).isZero();
    }

    // --- Ayudas ---

    /** Emite un comprobante de venta en la transacción en curso, con las entidades leídas en ella. */
    private Comprobante emitir() {
        Ambito a = ambitos.findById(local.getId()).orElseThrow();
        Articulo m = articulos.findById(maceta.getId()).orElseThrow();
        return emisor.emitir(new Emision(a, TipoComprobante.comprobante_venta, null, true,
                List.of(Renglon.de(m, BigDecimal.ONE, new BigDecimal("100"))), usuario));
    }

    private int ultimoNumero() {
        return jdbc.queryForObject("""
                select ultimo_numero from punto_venta_comprobantes
                where punto_venta_id = ? and tipo = 'comprobante_venta' and letra = 'X'
                """, Integer.class, pv.getId());
    }

    private int cantidadDeComprobantes() {
        return jdbc.queryForObject("select count(*) from comprobantes where punto_venta_id = ?", Integer.class,
                pv.getId());
    }

    private static void esperar(CountDownLatch senal) {
        try {
            senal.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
