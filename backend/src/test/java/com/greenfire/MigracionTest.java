package com.greenfire;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@PruebaIntegracion
class MigracionTest {

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void creaLasTablasDeLaEtapa1() {
        List<String> tablas = jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema = 'public'", String.class);

        assertThat(tablas).contains("entidades_fiscales", "ambitos", "puntos_venta", "punto_venta_comprobantes",
                "usuarios", "usuario_ambitos", "auditorias", "spring_session", "spring_session_attributes");
    }

    @Test
    void todasLasTablasDelModeloTienenFechasDeAltaYModificacion() {
        List<String> sinFechas = jdbc.queryForList("""
                select t.table_name from information_schema.tables t
                where t.table_schema = 'public' and t.table_name not like 'spring_session%'
                  and t.table_name <> 'flyway_schema_history'
                  and (select count(*) from information_schema.columns c
                       where c.table_name = t.table_name and c.column_name in ('created_at', 'updated_at')) < 2
                """, String.class);

        assertThat(sinFechas).isEmpty();
    }

    @Test
    void soloPuedeHaberUnaFabrica() {
        long entidad = crearEntidad("20111111112");
        crearAmbito("fabrica", entidad);

        assertThatThrownBy(() -> crearAmbito("fabrica", entidad))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void elPuntoDeVentaDebeSerDeLaMismaEntidadQueSuAmbito() {
        long entidadLocal = crearEntidad("20222222223");
        long otraEntidad = crearEntidad("20333333334");
        long local = crearAmbito("local", entidadLocal);

        assertThatThrownBy(() -> jdbc.update(
                "insert into puntos_venta (entidad_fiscal_id, ambito_id, numero) values (?, ?, 1)", otraEntidad, local))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void elCuitDebeTenerOnceDigitos() {
        assertThatThrownBy(() -> crearEntidad("20-1111111-2"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void elEmailDelUsuarioEsUnicoSinImportarMayusculas() {
        jdbc.update("insert into usuarios (nombre, email, password_hash) values ('A', 'ana@greenfire.com', 'x')");

        assertThatThrownBy(() -> jdbc.update(
                "insert into usuarios (nombre, email, password_hash) values ('B', 'Ana@GreenFire.com', 'x')"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private long crearEntidad(String cuit) {
        return jdbc.queryForObject("""
                insert into entidades_fiscales (razon_social, cuit, condicion_iva)
                values ('Entidad', ?, 'responsable_inscripto') returning id
                """, Long.class, cuit);
    }

    private long crearAmbito(String tipo, long entidad) {
        return jdbc.queryForObject(
                "insert into ambitos (tipo, nombre, entidad_fiscal_id) values (?, 'Ámbito', ?) returning id",
                Long.class, tipo, entidad);
    }
}
