package com.greenfire.auditoria;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.greenfire.ambitos.Ambito;
import com.greenfire.usuarios.Usuario;

import jakarta.persistence.EntityManager;

/**
 * Guarda en la tabla auditorias cada {@link EventoAuditoria}: un registro por ámbito afectado, o uno solo si
 * la operación no toca a ningún ámbito.
 *
 * Corre en la misma transacción que la operación auditada (MANDATORY: sin transacción, falla). Si el insert
 * falla, la excepción revierte toda la transacción: o quedan la operación y su auditoría, o ninguna de las dos.
 */
@Component
public class RegistroAuditoria {

    private final AuditoriaRepository auditorias;
    private final EntityManager em;
    private final ObjectMapper json;

    public RegistroAuditoria(AuditoriaRepository auditorias, EntityManager em, ObjectMapper json) {
        this.auditorias = auditorias;
        this.em = em;
        this.json = json;
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void guardar(EventoAuditoria evento) {
        try {
            String detalle = json.writeValueAsString(Redactor.limpiar(evento.detalle()));
            OffsetDateTime ahora = OffsetDateTime.now();
            List<Long> afectados = evento.ambitosAfectados().isEmpty()
                    ? Collections.singletonList(null)
                    : evento.ambitosAfectados();
            for (Long afectado : afectados) {
                auditorias.save(new Auditoria(
                        evento.usuarioId() == null ? null : em.getReference(Usuario.class, evento.usuarioId()),
                        evento.perfil(),
                        ambito(evento.ambitoId()),
                        ambito(afectado),
                        evento.accion(),
                        evento.entidad(),
                        evento.entidadId(),
                        detalle,
                        evento.emailIntentado(),
                        evento.ip(),
                        ahora));
            }
            // Con ids IDENTITY, save() ejecuta el INSERT en el momento: un error sale acá, no al confirmar.
        } catch (RuntimeException | JsonProcessingException e) {
            throw new AuditoriaException(e);
        }
    }

    private Ambito ambito(Long id) {
        return id == null ? null : em.getReference(Ambito.class, id);
    }
}
