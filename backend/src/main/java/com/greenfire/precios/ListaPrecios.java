package com.greenfire.precios;

import com.greenfire.ambitos.Ambito;
import com.greenfire.comun.EntidadBase;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Lista de precios de un ámbito: en la fábrica, las listas para locales; en un local, la de venta al público
 * (la predeterminada) y las que agregue. Si la entidad del ámbito no discrimina IVA, va siempre con IVA.
 */
@Entity
@Table(name = "listas_precios")
public class ListaPrecios extends EntidadBase {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Ambito ambito;
    private String nombre;
    private boolean preciosConIva;
    private boolean esPredeterminada;
    private boolean activo = true;

    public Ambito getAmbito() {
        return ambito;
    }

    public void setAmbito(Ambito ambito) {
        this.ambito = ambito;
    }

    public String getNombre() {
        return nombre;
    }

    public void setNombre(String nombre) {
        this.nombre = nombre;
    }

    public boolean isPreciosConIva() {
        return preciosConIva;
    }

    public void setPreciosConIva(boolean preciosConIva) {
        this.preciosConIva = preciosConIva;
    }

    public boolean isEsPredeterminada() {
        return esPredeterminada;
    }

    public void setEsPredeterminada(boolean esPredeterminada) {
        this.esPredeterminada = esPredeterminada;
    }

    public boolean isActivo() {
        return activo;
    }

    public void setActivo(boolean activo) {
        this.activo = activo;
    }
}
