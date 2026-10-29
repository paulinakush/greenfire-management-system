package com.greenfire.fiscal;

import java.time.LocalDate;

import com.greenfire.comun.EntidadBase;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

@Entity
@Table(name = "entidades_fiscales")
public class EntidadFiscal extends EntidadBase {

    private String razonSocial;
    private String cuit;
    @Enumerated(EnumType.STRING)
    private CondicionIva condicionIva;
    private String domicilioFiscal;
    private String arcaCertificadoRef;
    private LocalDate arcaCertificadoVence;
    private boolean activo = true;

    public String getRazonSocial() {
        return razonSocial;
    }

    public void setRazonSocial(String razonSocial) {
        this.razonSocial = razonSocial;
    }

    public String getCuit() {
        return cuit;
    }

    public void setCuit(String cuit) {
        this.cuit = cuit;
    }

    public CondicionIva getCondicionIva() {
        return condicionIva;
    }

    public void setCondicionIva(CondicionIva condicionIva) {
        this.condicionIva = condicionIva;
    }

    public String getDomicilioFiscal() {
        return domicilioFiscal;
    }

    public void setDomicilioFiscal(String domicilioFiscal) {
        this.domicilioFiscal = domicilioFiscal;
    }

    public String getArcaCertificadoRef() {
        return arcaCertificadoRef;
    }

    public void setArcaCertificadoRef(String arcaCertificadoRef) {
        this.arcaCertificadoRef = arcaCertificadoRef;
    }

    public LocalDate getArcaCertificadoVence() {
        return arcaCertificadoVence;
    }

    public void setArcaCertificadoVence(LocalDate arcaCertificadoVence) {
        this.arcaCertificadoVence = arcaCertificadoVence;
    }

    public boolean isActivo() {
        return activo;
    }

    public void setActivo(boolean activo) {
        this.activo = activo;
    }
}
