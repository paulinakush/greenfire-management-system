package com.greenfire.precios;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.greenfire.Datos;
import com.greenfire.PruebaIntegracion;
import com.greenfire.ambitos.Ambito;
import com.greenfire.ambitos.TipoAmbito;
import com.greenfire.catalogo.TipoArticulo;
import com.greenfire.fiscal.CondicionIva;

/** Reglas de las listas de precios que garantiza la base, aunque se inserte por fuera de la aplicación. */
@PruebaIntegracion
class PreciosMigracionTest {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    Datos datos;

    Ambito fabrica;
    Ambito monotributo;
    Ambito inscripto;
    long sustratos;

    @BeforeEach
    void ambitos() {
        fabrica = datos.fabrica();
        monotributo = datos.local("Centro");
        inscripto = datos.ambito(TipoAmbito.local, "Norte", CondicionIva.responsable_inscripto);
        sustratos = datos.categoria("Sustratos").getId();
    }

    @Test
    void siLaEntidadNoDiscriminaIvaLaListaVaConIva() {
        rechaza(() -> lista(monotributo.getId(), "Sin IVA", false, false));
        lista(monotributo.getId(), "Con IVA", true, false);
        lista(inscripto.getId(), "Sin IVA", false, false);
    }

    @Test
    void hayUnaSolaListaPredeterminadaPorAmbitoYNoSePuedeDesactivar() {
        lista(inscripto.getId(), "Público", true, true);
        rechaza(() -> lista(inscripto.getId(), "Otra", true, true));
        lista(monotributo.getId(), "Público", true, true);
        rechaza(() -> jdbc.update("""
                insert into listas_precios (ambito_id, nombre, precios_con_iva, es_predeterminada, activo)
                values (?, 'Inactiva', true, true, false)
                """, fabrica.getId()));
    }

    @Test
    void unaListaNoTienePreciosDeInsumosNiDeLaReventaDeOtroLocal() {
        long insumo = datos.articulo(fabrica, TipoArticulo.insumo, "Turba", null).getId();
        long reventaNorte = datos.articulo(inscripto, TipoArticulo.reventa, "Maceta", datos.categoria("Macetas"))
                .getId();
        long producto = datos.articulo(fabrica, TipoArticulo.producto_terminado, "Coco", datos.categoria("Coco"))
                .getId();
        long listaFabrica = lista(fabrica.getId(), "Locales", true, false);
        long listaCentro = lista(monotributo.getId(), "Público", true, true);

        rechaza(() -> item(listaFabrica, insumo, "10"));
        rechaza(() -> item(listaCentro, reventaNorte, "10"));
        rechaza(() -> item(listaCentro, producto, "0"));
        item(listaCentro, producto, "10");
        item(listaFabrica, producto, "8");
    }

    @Test
    void elHistorialDePreciosNoSeModificaNiSeBorra() {
        long producto = datos.articulo(fabrica, TipoArticulo.producto_terminado, "Coco", datos.categoria("Coco"))
                .getId();
        long lista = lista(fabrica.getId(), "Locales", true, false);
        long usuario = datos.usuario("Ana").getId();
        long id = jdbc.queryForObject("""
                insert into historial_precios (lista_id, articulo_id, precio_anterior, precio_nuevo, usuario_id)
                values (?, ?, null, 100, ?) returning id
                """, Long.class, lista, producto, usuario);

        rechazaModificacion(() -> jdbc.update("update historial_precios set precio_nuevo = 1 where id = ?", id));
        rechazaModificacion(() -> jdbc.update("delete from historial_precios where id = ?", id));
        rechazaModificacion(() -> jdbc.execute("truncate historial_precios cascade"));
        assertThat(jdbc.queryForObject("select precio_nuevo from historial_precios where id = ?", Integer.class, id))
                .isEqualTo(100);
    }

    private void rechaza(Runnable insert) {
        jdbc.execute("savepoint intento");
        assertThatThrownBy(insert::run).isInstanceOf(DataIntegrityViolationException.class);
        jdbc.execute("rollback to savepoint intento");
    }

    private void rechazaModificacion(Runnable operacion) {
        jdbc.execute("savepoint intento");
        assertThatThrownBy(operacion::run).isInstanceOf(DataAccessException.class)
                .rootCause().hasMessageContaining("no se puede modificar ni borrar");
        jdbc.execute("rollback to savepoint intento");
    }

    private long lista(long ambito, String nombre, boolean conIva, boolean predeterminada) {
        return jdbc.queryForObject("""
                insert into listas_precios (ambito_id, nombre, precios_con_iva, es_predeterminada)
                values (?, ?, ?, ?) returning id
                """, Long.class, ambito, nombre, conIva, predeterminada);
    }

    private void item(long lista, long articulo, String precio) {
        jdbc.update("insert into lista_precios_items (lista_id, articulo_id, precio) values (?, ?, ?::numeric)",
                lista, articulo, precio);
    }
}
