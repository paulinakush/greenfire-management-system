package com.greenfire.entregas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.greenfire.Datos;
import com.greenfire.PruebaIntegracion;
import com.greenfire.ambitos.Ambito;
import com.greenfire.catalogo.Articulo;
import com.greenfire.catalogo.Categoria;
import com.greenfire.catalogo.TipoArticulo;

/** Reglas de las entregas que garantiza la base, aunque se escriba por fuera de la aplicación (V9). */
@PruebaIntegracion
class EntregaMigracionTest {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    Datos datos;

    Ambito fabrica;
    Ambito centro;
    Ambito norte;
    Articulo coco;
    Articulo perlita;
    Articulo maceta;
    long origen;
    long destino;
    long destinoNorte;
    long lista;
    long listaLocal;
    long usuario;

    @BeforeEach
    void datos() {
        fabrica = datos.fabrica();
        centro = datos.local("Centro");
        norte = datos.local("Norte");
        Categoria categoria = datos.categoria("Sustratos");
        coco = datos.articulo(fabrica, TipoArticulo.producto_terminado, "Coco", categoria);
        perlita = datos.articulo(fabrica, TipoArticulo.insumo, "Perlita", null);
        maceta = datos.articulo(centro, TipoArticulo.reventa, "Maceta", categoria);
        lista = datos.lista(fabrica, "Para locales", false, true).getId();
        listaLocal = datos.lista(centro, "Público", true, true).getId();
        origen = datos.principal(fabrica).getId();
        destino = datos.principal(centro).getId();
        destinoNorte = datos.principal(norte).getId();
        usuario = datos.usuario("Ana").getId();
        jdbc.update("insert into movimientos_stock (articulo_id, ubicacion_stock_id, tipo, cantidad, usuario_id, motivo) "
                + "values (?, ?, 'ajuste', 100, ?, 'Carga')", coco.getId(), origen, usuario);
    }

    // --- Estados ---

    @Test
    void enviadaSoloPasaARecibidaDiferenciaInformadaOAnulada() {
        long recibida = entrega();
        recibir(recibida, "recibida");
        rechazaModificacion(() -> jdbc.update(anular(), usuario, recibida));
        rechazaModificacion(() -> jdbc.update("update entregas set estado = 'diferencia_informada' where id = ?", recibida));
        rechazaModificacion(() -> jdbc.update("update entregas set observaciones_recepcion = 'x' where id = ?", recibida));

        long anulada = entrega();
        jdbc.update(anular(), usuario, anulada);
        rechazaModificacion(() -> recibir(anulada, "recibida"));
        rechazaModificacion(() -> jdbc.update("update entregas set anulada_at = now() where id = ?", anulada));

        long conDiferencia = entrega();
        recibir(conDiferencia, "diferencia_informada");
        rechazaModificacion(() -> jdbc.update("""
                update entregas set estado = 'recibida' where id = ?
                """, conDiferencia));

        long enviada = entrega();
        rechazaModificacion(() -> jdbc.update("update entregas set observaciones_recepcion = 'x' where id = ?", enviada));
    }

    @Test
    void losDatosDelEnvioNoCambian() {
        long id = entrega();
        rechazaModificacion(() -> jdbc.update("update entregas set total = 1 where id = ?", id));
        rechazaModificacion(() -> jdbc.update("update entregas set ambito_destino_id = ? where id = ?", norte.getId(), id));
        rechazaModificacion(() -> jdbc.update("update entregas set lista_precios_id = ? where id = ?", listaLocal, id));
    }

    @Test
    void cadaEstadoLlevaSusDatos() {
        long id = entrega();
        rechaza(() -> jdbc.update("update entregas set estado = 'recibida' where id = ?", id));
        rechaza(() -> jdbc.update("update entregas set estado = 'anulada' where id = ?", id));
        rechaza(() -> jdbc.update("update entregas set estado = 'perdida' where id = ?", id));
        rechaza(() -> jdbc.update("""
                update entregas set estado = 'anulada', anulada_at = now(), usuario_anulacion_id = ?,
                       fecha_recepcion = now() where id = ?
                """, usuario, id));
    }

    @Test
    void unaDiferenciaSeResuelveUnaVezConMotivo() {
        long recibida = entrega();
        recibir(recibida, "recibida");
        rechaza(() -> resolver(recibida, "perdida", "Rotura"));

        long id = entrega();
        recibir(id, "diferencia_informada");
        rechaza(() -> resolver(id, "perdida", "  "));
        rechaza(() -> resolver(id, "otra", "Rotura"));
        resolver(id, "perdida", "Rotura");
        rechazaModificacion(() -> resolver(id, "reingreso", "Apareció"));
        rechazaModificacion(() -> jdbc.update("update entregas set motivo_resolucion = 'Otro' where id = ?", id));
    }

    @Test
    void ningunaEntregaNiRenglonSeBorra() {
        // antes de insertar: con la verificación diferida del total pendiente, PostgreSQL ni intenta el TRUNCATE
        rechazaModificacion(() -> jdbc.execute("truncate entregas cascade"));
        rechazaModificacion(() -> jdbc.execute("truncate detalle_entregas"));
        long id = entrega();
        rechazaModificacion(() -> jdbc.update("delete from detalle_entregas where entrega_id = ?", id));
        rechazaModificacion(() -> jdbc.update("delete from entregas where id = ?", id));
        assertThat(jdbc.queryForObject("select count(*) from entregas where id = ?", Integer.class, id)).isOne();
    }

    // --- Origen, destino, ubicaciones y lista ---

    @Test
    void vaDeLaFabricaAUnLocalConUbicacionesYListaDeCadaLado() {
        rechaza(() -> entrega(centro.getId(), norte.getId(), destino, listaLocal));
        rechaza(() -> entrega(fabrica.getId(), fabrica.getId(), origen, lista));
        rechaza(() -> entrega(fabrica.getId(), centro.getId(), destino, lista));
        rechaza(() -> entrega(fabrica.getId(), centro.getId(), origen, listaLocal));
        rechaza(() -> jdbc.update("""
                insert into entregas (ambito_origen_id, ambito_destino_id, ubicacion_origen_id, lista_precios_id,
                                      usuario_envio_id, total, estado, ubicacion_destino_id, usuario_recepcion_id,
                                      fecha_recepcion)
                values (?, ?, ?, ?, ?, 0, 'recibida', ?, ?, now())
                """, fabrica.getId(), centro.getId(), origen, lista, usuario, destino, usuario));

        long id = entrega();
        rechaza(() -> jdbc.update("""
                update entregas set estado = 'recibida', ubicacion_destino_id = ?, usuario_recepcion_id = ?,
                       fecha_recepcion = now() where id = ?
                """, destinoNorte, usuario, id));
    }

    // --- Renglones ---

    @Test
    void unRenglonRecibidoNuncaEsMayorQueElEnviado() {
        long id = entrega();
        long renglon = renglonDe(id);

        rechaza(() -> jdbc.update("update detalle_entregas set cantidad_recibida = 4 where id = ?", renglon),
                "detalle_entregas_recibida_no_mayor");
        rechaza(() -> jdbc.update("update detalle_entregas set cantidad_recibida = -1 where id = ?", renglon));
        rechaza(() -> jdbc.update("update detalle_entregas set cantidad_recibida = 1.5 where id = ?", renglon));
        jdbc.update("update detalle_entregas set cantidad_recibida = 3 where id = ?", renglon);
        // y se carga una sola vez
        rechazaModificacion(() -> jdbc.update("update detalle_entregas set cantidad_recibida = 2 where id = ?", renglon));
    }

    @Test
    void elDetalleSoloLlevaProductosTerminadosDeLaFabricaConSubtotalCorrecto() {
        long id = entrega();

        rechaza(() -> renglon(id, perlita.getId(), "1", "10", "10"));
        rechaza(() -> renglon(id, maceta.getId(), "1", "10", "10"));
        rechaza(() -> renglon(id, coco.getId(), "1", "1000", "1000")); // ya está, sin lote
        renglon(id, coco.getId(), 7L, "1", "1000", "1000"); // otro lote
        rechaza(() -> renglon(id, coco.getId(), 8L, "2", "333.33", "666.67"));
        rechaza(() -> renglon(id, coco.getId(), 9L, "1.5", "10", "15"));
        rechaza(() -> renglon(id, coco.getId(), 10L, "0", "10", "0"));
        rechazaModificacion(() -> jdbc.update("update detalle_entregas set precio_unitario = 1 where entrega_id = ?", id));
    }

    @Test
    void alConfirmarUnaEntregaTieneQueTenerRenglones() {
        entrega(fabrica.getId(), centro.getId(), origen, lista, "0");
        rechazaAlVerificar("no tiene renglones");
    }

    @Test
    void alConfirmarElTotalTieneQueSerLaSumaDeLosRenglones() {
        long id = entrega(fabrica.getId(), centro.getId(), origen, lista, "999");
        renglon(id, coco.getId(), "1", "1000", "1000");
        rechazaAlVerificar("no es la suma de sus renglones");
    }

    @Test
    void unaEntregaConRenglonesYSuTotalPasaLaVerificacion() {
        long id = entrega(fabrica.getId(), centro.getId(), origen, lista, "5000");
        renglon(id, coco.getId(), "3", "1000", "3000");
        renglon(id, coco.getId(), 7L, "2", "1000", "2000");

        jdbc.execute("set constraints all immediate");
    }

    @Test
    void unRenglonAgregadoDespuesDescuadraElTotal() {
        long id = entrega();
        renglon(id, coco.getId(), 7L, "2", "1000", "2000"); // el total sigue en 3000 y no se puede editar
        rechazaAlVerificar("no es la suma de sus renglones");
    }

    // --- Movimientos ---

    @Test
    void losMovimientosDeUnaEntregaApuntanAEllaEnLaUbicacionQueCorresponde() {
        long id = entrega();

        rechaza(() -> movimiento("entrega_salida", origen, "-1", null));
        rechaza(() -> movimiento("ajuste", origen, "-1", id));
        rechaza(() -> jdbc.update("""
                insert into movimientos_stock (articulo_id, ubicacion_stock_id, tipo, cantidad, usuario_id, motivo,
                                               entrega_id)
                values (?, ?, 'ajuste', 1, ?, 'Carga', ?)
                """, coco.getId(), origen, usuario, id), "movimientos_stock_entrega_segun_tipo");
        rechaza(() -> movimiento("entrega_salida", destino, "-1", id));
        rechaza(() -> movimiento("entrega_salida", origen, "1", id));
        rechaza(() -> movimiento("anulacion", origen, "-1", id));
        rechaza(() -> movimiento("entrega_recepcion", destino, "1", id)); // todavía no tiene ubicación de destino
        movimiento("entrega_salida", origen, "-3", id);
        movimiento("anulacion", origen, "3", id);

        long recibida = entrega();
        recibir(recibida, "recibida");
        rechaza(() -> movimiento("entrega_recepcion", destinoNorte, "1", recibida));
        movimiento("entrega_recepcion", destino, "3", recibida);
    }

    @Test
    void elArticuloDelMovimientoTieneQueSerDeLaEntrega() {
        Articulo turba = datos.articulo(fabrica, TipoArticulo.producto_terminado, "Turba",
                datos.categoria("Turbas"));
        jdbc.update("insert into movimientos_stock (articulo_id, ubicacion_stock_id, tipo, cantidad, usuario_id, motivo) "
                + "values (?, ?, 'ajuste', 10, ?, 'Carga')", turba.getId(), origen, usuario);
        long id = entrega();

        rechaza(() -> jdbc.update("""
                insert into movimientos_stock (articulo_id, ubicacion_stock_id, tipo, cantidad, usuario_id, entrega_id)
                values (?, ?, 'entrega_salida', -1, ?, ?)
                """, turba.getId(), origen, usuario, id));
    }

    // --- Ayudas ---

    /** Una entrega enviada de 3 de coco a 1000, del Principal de la fábrica al Centro. */
    private long entrega() {
        long id = entrega(fabrica.getId(), centro.getId(), origen, lista, "3000");
        renglon(id, coco.getId(), "3", "1000", "3000");
        return id;
    }

    private long entrega(long ambitoOrigen, long ambitoDestino, long ubicacion, long listaId) {
        return entrega(ambitoOrigen, ambitoDestino, ubicacion, listaId, "0");
    }

    private long entrega(long ambitoOrigen, long ambitoDestino, long ubicacion, long listaId, String total) {
        return jdbc.queryForObject("""
                insert into entregas (ambito_origen_id, ambito_destino_id, ubicacion_origen_id, lista_precios_id,
                                      usuario_envio_id, total)
                values (?, ?, ?, ?, ?, ?::numeric) returning id
                """, Long.class, ambitoOrigen, ambitoDestino, ubicacion, listaId, usuario, total);
    }

    private void renglon(long entrega, long articulo, String cantidad, String precio, String subtotal) {
        renglon(entrega, articulo, null, cantidad, precio, subtotal);
    }

    private void renglon(long entrega, long articulo, Long lote, String cantidad, String precio, String subtotal) {
        jdbc.update("""
                insert into detalle_entregas (entrega_id, articulo_id, lote_id, cantidad_enviada, precio_unitario,
                                              subtotal)
                values (?, ?, ?, ?::numeric, ?::numeric, ?::numeric)
                """, entrega, articulo, lote, cantidad, precio, subtotal);
    }

    private long renglonDe(long entrega) {
        return jdbc.queryForObject("select min(id) from detalle_entregas where entrega_id = ?", Long.class, entrega);
    }

    private void recibir(long id, String estado) {
        jdbc.update("""
                update entregas set estado = ?, ubicacion_destino_id = ?, usuario_recepcion_id = ?,
                       fecha_recepcion = now() where id = ?
                """, estado, destino, usuario, id);
    }

    private static String anular() {
        return "update entregas set estado = 'anulada', anulada_at = now(), usuario_anulacion_id = ? where id = ?";
    }

    private void resolver(long id, String forma, String motivo) {
        jdbc.update("""
                update entregas set resolucion_diferencia = ?, motivo_resolucion = ?, usuario_resolucion_id = ?,
                       diferencia_resuelta_at = now() where id = ?
                """, forma, motivo, usuario, id);
    }

    private void movimiento(String tipo, long ubicacion, String cantidad, Long entrega) {
        jdbc.update("""
                insert into movimientos_stock (articulo_id, ubicacion_stock_id, tipo, cantidad, usuario_id, motivo,
                                               entrega_id)
                values (?, ?, ?, ?::numeric, ?, 'Prueba', ?)
                """, coco.getId(), ubicacion, tipo, cantidad, usuario, entrega);
    }

    /** Cada intento corre en un savepoint, así un rechazo no aborta la transacción del test. */
    private void enSavepoint(Runnable operacion) {
        jdbc.execute("savepoint intento");
        try {
            operacion.run();
        } finally {
            jdbc.execute("rollback to savepoint intento");
        }
    }

    private void rechaza(Runnable operacion) {
        assertThatThrownBy(() -> enSavepoint(operacion)).isInstanceOf(DataAccessException.class);
    }

    private void rechaza(Runnable operacion, String restriccion) {
        assertThatThrownBy(() -> enSavepoint(operacion)).isInstanceOf(DataAccessException.class)
                .hasMessageContaining(restriccion);
    }

    private void rechazaModificacion(Runnable operacion) {
        assertThatThrownBy(() -> enSavepoint(operacion)).isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageMatching("(?s).*(no se puede modificar|no se pueden modificar|no se modifica"
                        + "|no se borra|solo se carga|sigue enviada).*");
    }

    /** Las verificaciones diferidas corren al confirmar; acá se adelantan con SET CONSTRAINTS. */
    private void rechazaAlVerificar(String mensaje) {
        assertThatThrownBy(() -> enSavepoint(() -> jdbc.execute("set constraints all immediate")))
                .isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining(mensaje);
    }
}
