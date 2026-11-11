package com.greenfire.ambitos;

import java.math.BigDecimal;

import com.greenfire.comun.EntidadBase;
import com.greenfire.fiscal.EntidadFiscal;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** La fábrica o un local. Cada uno tiene su propia entidad fiscal. */
@Entity
@Table(name = "ambitos")
public class Ambito extends EntidadBase {

    @Enumerated(EnumType.STRING)
    private TipoAmbito tipo;
    private String nombre;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private EntidadFiscal entidadFiscal;
    private String direccion;
    private String telefono;
    private String email;
    private String horarios;
    private boolean visibleWeb;
    private boolean esCentral;
    private boolean activo = true;
    private BigDecimal descuentoMaximoEmpleado = new BigDecimal("0.00");

    public TipoAmbito getTipo() {
        return tipo;
    }

    public void setTipo(TipoAmbito tipo) {
        this.tipo = tipo;
    }

    public String getNombre() {
        return nombre;
    }

    public void setNombre(String nombre) {
        this.nombre = nombre;
    }

    public EntidadFiscal getEntidadFiscal() {
        return entidadFiscal;
    }

    public void setEntidadFiscal(EntidadFiscal entidadFiscal) {
        this.entidadFiscal = entidadFiscal;
    }

    public String getDireccion() {
        return direccion;
    }

    public void setDireccion(String direccion) {
        this.direccion = direccion;
    }

    public String getTelefono() {
        return telefono;
    }

    public void setTelefono(String telefono) {
        this.telefono = telefono;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getHorarios() {
        return horarios;
    }

    public void setHorarios(String horarios) {
        this.horarios = horarios;
    }

    public boolean isVisibleWeb() {
        return visibleWeb;
    }

    public void setVisibleWeb(boolean visibleWeb) {
        this.visibleWeb = visibleWeb;
    }

    public boolean isEsCentral() {
        return esCentral;
    }

    public void setEsCentral(boolean esCentral) {
        this.esCentral = esCentral;
    }

    public boolean isActivo() {
        return activo;
    }

    public void setActivo(boolean activo) {
        this.activo = activo;
    }

    /** Porcentaje máximo de descuento que el empleado aplica en una venta del local (0 a 100). */
    public BigDecimal getDescuentoMaximoEmpleado() {
        return descuentoMaximoEmpleado;
    }

    public void setDescuentoMaximoEmpleado(BigDecimal descuentoMaximoEmpleado) {
        this.descuentoMaximoEmpleado = descuentoMaximoEmpleado;
    }
}
