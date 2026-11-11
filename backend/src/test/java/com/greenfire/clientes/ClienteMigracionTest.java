package com.greenfire.clientes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.greenfire.Datos;
import com.greenfire.PruebaIntegracion;
import com.greenfire.ambitos.Ambito;
import com.greenfire.precios.ListaPrecios;

/** Las reglas de clientes que garantiza la base (V11), probadas directamente con SQL. */
@PruebaIntegracion
class ClienteMigracionTest {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    Datos datos;

    @Test
    void alCrearUnLocalLaBaseLoDaDeAltaComoClienteDeLaFabrica() {
        Ambito fabrica = datos.fabrica();
        Ambito centro = datos.local("Centro");

        Map<String, Object> cliente = clienteDe(centro);
        assertThat(cliente.get("ambito_id")).isEqualTo(fabrica.getId());
        assertThat(cliente.get("nombre")).isEqualTo("Centro");
        assertThat(cliente.get("documento_tipo")).isEqualTo("cuit");
        assertThat(cliente.get("documento_numero")).isEqualTo(centro.getEntidadFiscal().getCuit());
        assertThat(cliente.get("condicion_iva")).isEqualTo("monotributo");
        assertThat(cliente.get("cuenta_corriente_habilitada")).isEqualTo(true);
    }

    @Test
    void siLaFabricaSeCreaDespuesRecibeLosLocalesQueYaExisten() {
        Ambito centro = datos.local("Centro");
        Ambito norte = datos.local("Norte");
        assertThat(jdbc.queryForObject("select count(*) from clientes where ambito_representado_id in (?, ?)",
                Long.class, centro.getId(), norte.getId())).isZero();

        Ambito fabrica = datos.fabrica();

        assertThat(clienteDe(centro).get("ambito_id")).isEqualTo(fabrica.getId());
        assertThat(clienteDe(norte).get("ambito_id")).isEqualTo(fabrica.getId());
    }

    @Test
    void unClienteNoSeBorra() {
        Cliente ana = datos.cliente(datos.local("Centro"), "Ana");

        assertThatThrownBy(() -> jdbc.update("delete from clientes where id = ?", ana.getId()))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void elAmbitoDelClienteNoCambia() {
        Cliente ana = datos.cliente(datos.local("Centro"), "Ana");
        Ambito norte = datos.local("Norte");

        assertThatThrownBy(() -> jdbc.update("update clientes set ambito_id = ? where id = ?", norte.getId(),
                ana.getId())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void laListaDePreciosTieneQueSerDelAmbitoDelCliente() {
        Ambito centro = datos.local("Centro");
        ListaPrecios deNorte = datos.lista(datos.local("Norte"), "Mayorista", true, false);
        Cliente ana = datos.cliente(centro, "Ana");

        assertThatThrownBy(() -> jdbc.update("update clientes set lista_precios_id = ? where id = ?", deNorte.getId(),
                ana.getId())).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void soloLaFabricaTieneClientesQueRepresentanAUnLocal() {
        Ambito centro = datos.local("Centro");
        Ambito norte = datos.local("Norte");

        assertThatThrownBy(() -> jdbc.update("""
                insert into clientes (ambito_id, nombre, documento_tipo, documento_numero, cuenta_corriente_habilitada,
                                      ambito_representado_id)
                values (?, 'Norte', 'cuit', '20123456786', true, ?)""", centro.getId(), norte.getId()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void elDocumentoTieneElFormatoDeSuTipoYLaCuentaCorrienteLoExige() {
        long centro = datos.local("Centro").getId();

        for (String[] invalido : new String[][] {{"cuit", "2012345678"}, {"dni", "123456"}, {"cuit", null},
                {null, "12345678"}, {"pasaporte", "12345678"}}) {
            jdbc.execute("savepoint antes");
            assertThatThrownBy(() -> jdbc.update(
                    "insert into clientes (ambito_id, nombre, documento_tipo, documento_numero) values (?, 'Ana', ?, ?)",
                    centro, invalido[0], invalido[1]))
                    .as(String.join(" ", String.valueOf(invalido[0]), String.valueOf(invalido[1])))
                    .isInstanceOf(DataIntegrityViolationException.class);
            jdbc.execute("rollback to savepoint antes");
        }
        assertThatThrownBy(() -> jdbc.update(
                "insert into clientes (ambito_id, nombre, cuenta_corriente_habilitada) values (?, 'Ana', true)", centro))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void elDocumentoEsUnicoDentroDelAmbito() {
        long centro = datos.local("Centro").getId();
        long norte = datos.local("Norte").getId();
        String insert = "insert into clientes (ambito_id, nombre, documento_tipo, documento_numero) "
                + "values (?, 'Ana', 'cuit', '20123456786')";
        jdbc.update(insert, centro);
        jdbc.update(insert, norte);

        assertThatThrownBy(() -> jdbc.update(insert, centro)).isInstanceOf(DataIntegrityViolationException.class);
    }

    private Map<String, Object> clienteDe(Ambito local) {
        return jdbc.queryForMap("select * from clientes where ambito_representado_id = ?", local.getId());
    }
}
