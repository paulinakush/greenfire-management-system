package com.greenfire.auditoria;

import java.time.OffsetDateTime;

import org.hibernate.annotations.Immutable;

import com.greenfire.ambitos.Ambito;
import com.greenfire.comun.EntidadBase;
import com.greenfire.seguridad.Perfil;
import com.greenfire.usuarios.Usuario;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Registro de auditoría. Solo se inserta: Hibernate no lo actualiza (@Immutable), el repositorio no expone
 * borrado y la base rechaza UPDATE, DELETE y TRUNCATE (migración V2).
 */
@Entity
@Immutable
@Table(name = "auditorias")
public class Auditoria extends EntidadBase {

    @ManyToOne(fetch = FetchType.LAZY)
    private Usuario usuario;
    @Enumerated(EnumType.STRING)
    private Perfil perfil;
    @ManyToOne(fetch = FetchType.LAZY)
    private Ambito ambito;
    @ManyToOne(fetch = FetchType.LAZY)
    private Ambito ambitoAfectado;
    private String accion;
    private String entidad;
    private Long entidadId;
    /** JSON, sin datos sensibles (ver {@link Redactor}). */
    private String detalle;
    private String emailIntentado;
    private String ip;
    private OffsetDateTime fechaHora;

    protected Auditoria() {
    }

    Auditoria(Usuario usuario, Perfil perfil, Ambito ambito, Ambito ambitoAfectado, String accion, String entidad, Long entidadId,
            String detalle, String emailIntentado, String ip, OffsetDateTime fechaHora) {
        this.usuario = usuario;
        this.perfil = perfil;
        this.ambito = ambito;
        this.ambitoAfectado = ambitoAfectado;
        this.accion = accion;
        this.entidad = entidad;
        this.entidadId = entidadId;
        this.detalle = detalle;
        this.emailIntentado = emailIntentado;
        this.ip = ip;
        this.fechaHora = fechaHora;
    }

    public Usuario getUsuario() {
        return usuario;
    }

    public Perfil getPerfil() {
        return perfil;
    }

    public Ambito getAmbito() {
        return ambito;
    }

    public Ambito getAmbitoAfectado() {
        return ambitoAfectado;
    }

    public String getAccion() {
        return accion;
    }

    public String getEntidad() {
        return entidad;
    }

    public Long getEntidadId() {
        return entidadId;
    }

    public String getDetalle() {
        return detalle;
    }

    public String getEmailIntentado() {
        return emailIntentado;
    }

    public String getIp() {
        return ip;
    }

    public OffsetDateTime getFechaHora() {
        return fechaHora;
    }
}
