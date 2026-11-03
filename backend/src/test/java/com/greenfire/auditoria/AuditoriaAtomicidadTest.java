package com.greenfire.auditoria;

import static com.greenfire.Datos.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import com.greenfire.Datos;
import com.greenfire.PruebaIntegracion;
import com.greenfire.ambitos.Ambito;
import com.greenfire.seguridad.Perfil;
import com.greenfire.seguridad.Sesion;
import com.greenfire.usuarios.Usuario;

/** Si no se puede guardar la auditoría, la operación que la generó no se aplica. */
@PruebaIntegracion
class AuditoriaAtomicidadTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    Datos datos;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    Auditor auditor;
    @Autowired
    PlatformTransactionManager transacciones;

    /** Sin la transacción de prueba: cada paso se confirma de verdad, para ver lo que queda en la base. */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void siFallaElRegistroDeAuditoriaLaEdicionNoSeAplica() throws Exception {
        Ambito local = datos.local("Atomicidad");
        Usuario dueno = datos.usuario("Dueno");
        datos.perfil(dueno, local, Perfil.dueno_local);
        var sesion = datos.sesion(Sesion.enAmbito(dueno.getId(), local.getId(), Perfil.dueno_local));
        // Hace fallar el insert de esta acción en la tabla auditorias.
        jdbc.execute("alter table auditorias add constraint prueba_falla check (accion <> 'editar_contacto') not valid");
        try {
            mvc.perform(json(put("/api/ambito/contacto"), sesion, "{\"direccion\": \"Nueva dirección 123\"}"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.mensaje").value(
                            "No se pudo registrar la auditoría, así que la operación no se realizó."));

            assertThat(jdbc.queryForObject("select direccion from ambitos where id = ?", String.class, local.getId()))
                    .isNull();
            assertThat(jdbc.queryForObject("select count(*) from auditorias where ambito_id = ?", Long.class,
                    local.getId())).isZero();
        } finally {
            jdbc.execute("alter table auditorias drop constraint prueba_falla");
            jdbc.update("delete from usuario_ambitos where ambito_id = ?", local.getId());
            jdbc.update("delete from usuarios where id = ?", dueno.getId());
            jdbc.update("delete from ubicaciones_stock where ambito_id = ?", local.getId()); // la "Principal" (V6)
            jdbc.update("delete from ambitos where id = ?", local.getId());
            jdbc.update("delete from entidades_fiscales where id = ?", local.getEntidadFiscal().getId());
        }
    }

    @Test
    void fueraDeUnaTransaccionNoSePuedeAuditar() {
        TransactionTemplate sinTransaccion = new TransactionTemplate(transacciones,
                new DefaultTransactionDefinition(TransactionDefinition.PROPAGATION_NOT_SUPPORTED));

        assertThatThrownBy(() -> sinTransaccion.executeWithoutResult(
                tx -> auditor.registrar("prueba", "x", 1L, Map.of())))
                .isInstanceOf(IllegalTransactionStateException.class);
    }
}
