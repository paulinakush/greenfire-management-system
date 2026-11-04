package com.greenfire.stock;

import com.greenfire.ambitos.Ambito;
import com.greenfire.comun.EntidadBase;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** Lugar físico donde hay stock dentro de un ámbito. Cada ámbito nace con la ubicación "Principal" (V6). */
@Entity
@Table(name = "ubicaciones_stock")
public class UbicacionStock extends EntidadBase {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Ambito ambito;
    private String nombre;
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

    public boolean isActivo() {
        return activo;
    }

    public void setActivo(boolean activo) {
        this.activo = activo;
    }

    public boolean esDe(Ambito otro) {
        return ambito.getId().equals(otro.getId());
    }
}
