package com.greenfire.stock;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import com.greenfire.comun.EntidadBase;
import com.greenfire.usuarios.Usuario;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Conteo físico de una ubicación. Mientras está abierto se cargan las cantidades contadas; al cerrarlo se toma la
 * cantidad del sistema y cada diferencia genera un movimiento de tipo conteo. Cerrado o anulado, la base no deja
 * modificarlo (V7). Hay un solo conteo abierto por ubicación.
 */
@Entity
@Table(name = "conteos_stock")
public class ConteoStock extends EntidadBase {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ubicacion_stock_id")
    private UbicacionStock ubicacion;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Usuario usuario;
    private LocalDate fecha;
    @Enumerated(EnumType.STRING)
    private EstadoConteo estado = EstadoConteo.abierto;
    private String observaciones;
    private OffsetDateTime cerradoAt;
    private OffsetDateTime anuladoAt;

    protected ConteoStock() {
    }

    ConteoStock(UbicacionStock ubicacion, Usuario usuario, LocalDate fecha, String observaciones) {
        this.ubicacion = ubicacion;
        this.usuario = usuario;
        this.fecha = fecha;
        this.observaciones = observaciones;
    }

    void cerrar() {
        estado = EstadoConteo.cerrado;
        cerradoAt = OffsetDateTime.now();
    }

    void anular() {
        estado = EstadoConteo.anulado;
        anuladoAt = OffsetDateTime.now();
    }

    public boolean abierto() {
        return estado == EstadoConteo.abierto;
    }

    public UbicacionStock getUbicacion() {
        return ubicacion;
    }

    public Usuario getUsuario() {
        return usuario;
    }

    public LocalDate getFecha() {
        return fecha;
    }

    public EstadoConteo getEstado() {
        return estado;
    }

    public String getObservaciones() {
        return observaciones;
    }

    public OffsetDateTime getCerradoAt() {
        return cerradoAt;
    }

    public OffsetDateTime getAnuladoAt() {
        return anuladoAt;
    }
}
