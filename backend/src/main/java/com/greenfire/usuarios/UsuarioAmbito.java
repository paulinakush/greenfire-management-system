package com.greenfire.usuarios;

import com.greenfire.ambitos.Ambito;
import com.greenfire.comun.EntidadBase;
import com.greenfire.seguridad.Perfil;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** El perfil que un usuario tiene en un ámbito. Un usuario tiene a lo sumo un perfil por ámbito. */
@Entity
@Table(name = "usuario_ambitos")
public class UsuarioAmbito extends EntidadBase {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Usuario usuario;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Ambito ambito;
    @Enumerated(EnumType.STRING)
    private Perfil perfil;
    private boolean activo = true;
    private Long creadoPorUsuarioId;

    /** Un perfil sirve para entrar solo si el usuario, el perfil y el ámbito están activos. */
    public boolean vigente() {
        return activo && usuario.isActivo() && ambito.isActivo();
    }

    public Usuario getUsuario() {
        return usuario;
    }

    public void setUsuario(Usuario usuario) {
        this.usuario = usuario;
    }

    public Ambito getAmbito() {
        return ambito;
    }

    public void setAmbito(Ambito ambito) {
        this.ambito = ambito;
    }

    public Perfil getPerfil() {
        return perfil;
    }

    public void setPerfil(Perfil perfil) {
        this.perfil = perfil;
    }

    public boolean isActivo() {
        return activo;
    }

    public void setActivo(boolean activo) {
        this.activo = activo;
    }

    public Long getCreadoPorUsuarioId() {
        return creadoPorUsuarioId;
    }

    public void setCreadoPorUsuarioId(Long creadoPorUsuarioId) {
        this.creadoPorUsuarioId = creadoPorUsuarioId;
    }
}
