package com.greenfire.catalogo;

import com.greenfire.comun.EntidadBase;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;

/** Común a todos los ámbitos; la administra el dueño de fábrica. */
@Entity
@Table(name = "categorias")
public class Categoria extends EntidadBase {

    private String nombre;
    private String descripcion;
    private boolean activo = true;

    public String getNombre() {
        return nombre;
    }

    public void setNombre(String nombre) {
        this.nombre = nombre;
    }

    public String getDescripcion() {
        return descripcion;
    }

    public void setDescripcion(String descripcion) {
        this.descripcion = descripcion;
    }

    public boolean isActivo() {
        return activo;
    }

    public void setActivo(boolean activo) {
        this.activo = activo;
    }
}
