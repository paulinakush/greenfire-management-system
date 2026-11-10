package com.greenfire.usuarios;

import java.time.OffsetDateTime;

import com.greenfire.comun.EntidadBase;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "usuarios")
public class Usuario extends EntidadBase {

    private String nombre;
    /** Para el login, junto con el email: minúsculas, números, punto y guion bajo (3 a 30). */
    private String usuario;
    private String email;
    private String passwordHash;
    private boolean esAdminTecnico;
    private boolean activo = true;
    private int intentosFallidos;
    private OffsetDateTime bloqueadoHasta;
    private String resetTokenHash;
    private OffsetDateTime resetTokenVence;
    private OffsetDateTime ultimoAcceso;

    public boolean estaBloqueado(OffsetDateTime ahora) {
        return bloqueadoHasta != null && bloqueadoHasta.isAfter(ahora);
    }

    public String getNombre() {
        return nombre;
    }

    public void setNombre(String nombre) {
        this.nombre = nombre;
    }

    public String getUsuario() {
        return usuario;
    }

    public void setUsuario(String usuario) {
        this.usuario = usuario;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public void setPasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    public boolean isEsAdminTecnico() {
        return esAdminTecnico;
    }

    public void setEsAdminTecnico(boolean esAdminTecnico) {
        this.esAdminTecnico = esAdminTecnico;
    }

    public boolean isActivo() {
        return activo;
    }

    public void setActivo(boolean activo) {
        this.activo = activo;
    }

    public int getIntentosFallidos() {
        return intentosFallidos;
    }

    public void setIntentosFallidos(int intentosFallidos) {
        this.intentosFallidos = intentosFallidos;
    }

    public OffsetDateTime getBloqueadoHasta() {
        return bloqueadoHasta;
    }

    public void setBloqueadoHasta(OffsetDateTime bloqueadoHasta) {
        this.bloqueadoHasta = bloqueadoHasta;
    }

    public String getResetTokenHash() {
        return resetTokenHash;
    }

    public void setResetTokenHash(String resetTokenHash) {
        this.resetTokenHash = resetTokenHash;
    }

    public OffsetDateTime getResetTokenVence() {
        return resetTokenVence;
    }

    public void setResetTokenVence(OffsetDateTime resetTokenVence) {
        this.resetTokenVence = resetTokenVence;
    }

    public OffsetDateTime getUltimoAcceso() {
        return ultimoAcceso;
    }

    public void setUltimoAcceso(OffsetDateTime ultimoAcceso) {
        this.ultimoAcceso = ultimoAcceso;
    }
}
