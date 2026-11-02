package com.greenfire.auditoria;

import java.time.OffsetDateTime;

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
 * Guarda en la tabla auditorias cada {@link EventoAuditoria}.
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
            auditorias.save(new Auditoria(
                    evento.usuarioId() == null ? null : em.getReference(Usuario.class, evento.usuarioId()),
                    evento.perfil(),
                    evento.ambitoId() == null ? null : em.getReference(Ambito.class, evento.ambitoId()),
                    evento.accion(),
                    evento.entidad(),
                    evento.entidadId(),
                    json.writeValueAsString(Redactor.limpiar(evento.detalle())),
                    evento.emailIntentado(),
                    evento.ip(),
                    OffsetDateTime.now()));
            // Con ids IDENTITY, save() ejecuta el INSERT en el momento: un error sale acá, no al confirmar.
        } catch (RuntimeException | JsonProcessingException e) {
            throw new AuditoriaException(e);
        }
    }
}
