package com.greenfire.caja;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import org.hibernate.annotations.Immutable;

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
 * Ingreso o egreso de una sesión de caja abierta. Solo se inserta (V13): un error se corrige con un movimiento
 * manual contrario que apunta al original ({@link #getCorrige()}).
 */
@Entity
@Immutable
@Table(name = "movimientos_caja")
public class MovimientoCaja extends EntidadBase {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sesion_caja_id")
    private SesionCaja sesion;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Usuario usuario;
    @Enumerated(EnumType.STRING)
    private OrigenMovimientoCaja origen;
    @Enumerated(EnumType.STRING)
    private TipoMovimientoCaja tipo;
    @Enumerated(EnumType.STRING)
    private RubroCaja rubro;
    @Enumerated(EnumType.STRING)
    private MedioPago medioPago;
    private BigDecimal monto;
    private OffsetDateTime fechaHora;
    private String observacion;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "corrige_movimiento_id")
    private MovimientoCaja corrige;

    protected MovimientoCaja() {
    }

    MovimientoCaja(SesionCaja sesion, Usuario usuario, OrigenMovimientoCaja origen, TipoMovimientoCaja tipo,
            RubroCaja rubro, MedioPago medioPago, BigDecimal monto, String observacion, MovimientoCaja corrige) {
        this.sesion = sesion;
        this.usuario = usuario;
        this.origen = origen;
        this.tipo = tipo;
        this.rubro = rubro;
        this.medioPago = medioPago;
        this.monto = monto;
        this.fechaHora = OffsetDateTime.now();
        this.observacion = observacion;
        this.corrige = corrige;
    }

    /** El monto con signo: positivo si entra, negativo si sale. */
    public BigDecimal montoConSigno() {
        return tipo == TipoMovimientoCaja.ingreso ? monto : monto.negate();
    }

    public SesionCaja getSesion() {
        return sesion;
    }

    public Usuario getUsuario() {
        return usuario;
    }

    public OrigenMovimientoCaja getOrigen() {
        return origen;
    }

    public TipoMovimientoCaja getTipo() {
        return tipo;
    }

    public RubroCaja getRubro() {
        return rubro;
    }

    public MedioPago getMedioPago() {
        return medioPago;
    }

    public BigDecimal getMonto() {
        return monto;
    }

    public OffsetDateTime getFechaHora() {
        return fechaHora;
    }

    public String getObservacion() {
        return observacion;
    }

    public MovimientoCaja getCorrige() {
        return corrige;
    }
}
