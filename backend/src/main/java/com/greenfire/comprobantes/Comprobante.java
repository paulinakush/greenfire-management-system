package com.greenfire.comprobantes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.DynamicUpdate;

import com.greenfire.clientes.Cliente;
import com.greenfire.clientes.TipoDocumento;
import com.greenfire.comun.EntidadBase;
import com.greenfire.fiscal.CondicionIva;
import com.greenfire.fiscal.LetraComprobante;
import com.greenfire.fiscal.PuntoVenta;
import com.greenfire.fiscal.ReglasComprobante;
import com.greenfire.fiscal.TipoComprobante;
import com.greenfire.usuarios.Usuario;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

/**
 * Comprobante emitido por un punto de venta. Lo crea solo {@link EmisorComprobantes}, con los datos del emisor y
 * del receptor copiados al emitir. No se modifica ni se borra: solo pasa a anulado (lo garantiza la base, V12).
 * Los campos de ARCA (arca_estado, cae, cae_vence) se mapean en la etapa 6.
 */
@Entity
@DynamicUpdate
@Table(name = "comprobantes")
public class Comprobante extends EntidadBase {

    @Enumerated(EnumType.STRING)
    private TipoComprobante tipo;
    @Enumerated(EnumType.STRING)
    private LetraComprobante letra;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private PuntoVenta puntoVenta;
    private Integer numero;

    private String emisorRazonSocial;
    private String emisorCuit;
    @Enumerated(EnumType.STRING)
    private CondicionIva emisorCondicionIva;
    private String emisorDomicilioFiscal;
    private String emisorNombre;
    private String emisorDireccion;

    @ManyToOne(fetch = FetchType.LAZY)
    private Cliente cliente;
    private String receptorRazonSocial;
    @Enumerated(EnumType.STRING)
    private TipoDocumento receptorDocumentoTipo;
    private String receptorDocumento;
    @Enumerated(EnumType.STRING)
    private CondicionIva receptorCondicionIva = CondicionIva.consumidor_final;
    private String receptorDomicilio;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private Usuario usuario;
    private OffsetDateTime fecha;
    private LocalDate fechaVencimiento;
    @Enumerated(EnumType.STRING)
    private EstadoComprobante estado = EstadoComprobante.emitido;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "anulado_por_usuario_id")
    private Usuario anuladoPor;
    private OffsetDateTime anuladoAt;
    private String motivoAnulacion;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "comprobante_asociado_id")
    private Comprobante asociado;
    private boolean preciosConIva;
    private BigDecimal neto;
    private BigDecimal iva;
    private BigDecimal total;
    private String observaciones;

    @OneToMany(mappedBy = "comprobante")
    @OrderBy("id")
    private List<DetalleComprobante> renglones = new ArrayList<>();

    protected Comprobante() {
    }

    Comprobante(TipoComprobante tipo, LetraComprobante letra, PuntoVenta puntoVenta, Integer numero,
            Usuario usuario) {
        this.tipo = tipo;
        this.letra = letra;
        this.puntoVenta = puntoVenta;
        this.numero = numero;
        this.usuario = usuario;
        this.fecha = OffsetDateTime.now();
        // Emisor: la entidad fiscal del punto de venta y su ámbito, tal como están al emitir.
        var entidad = puntoVenta.getEntidadFiscal();
        this.emisorRazonSocial = entidad.getRazonSocial();
        this.emisorCuit = entidad.getCuit();
        this.emisorCondicionIva = entidad.getCondicionIva();
        this.emisorDomicilioFiscal = entidad.getDomicilioFiscal();
        this.emisorNombre = puntoVenta.getAmbito().getNombre();
        this.emisorDireccion = puntoVenta.getAmbito().getDireccion();
    }

    /** Copia los datos del receptor tal como están al emitir. Sin cliente, es un consumidor final. */
    void copiarReceptor(Cliente c) {
        this.cliente = c;
        if (c != null) {
            this.receptorRazonSocial = c.getRazonSocial() != null && !c.getRazonSocial().isBlank()
                    ? c.getRazonSocial() : c.getNombre();
            this.receptorDocumentoTipo = c.getDocumentoTipo();
            this.receptorDocumento = c.getDocumentoNumero();
            this.receptorCondicionIva = c.getCondicionIva();
            this.receptorDomicilio = c.getDireccion();
        }
    }

    void fijarImportes(boolean preciosConIva, BigDecimal neto, BigDecimal iva, BigDecimal total) {
        this.preciosConIva = preciosConIva;
        this.neto = neto;
        this.iva = iva;
        this.total = total;
    }

    void fijarOtros(Comprobante asociado, LocalDate fechaVencimiento, String observaciones) {
        this.asociado = asociado;
        this.fechaVencimiento = fechaVencimiento;
        this.observaciones = observaciones;
    }

    void anular(Usuario usuario, String motivo) {
        this.estado = EstadoComprobante.anulado;
        this.anuladoPor = usuario;
        this.anuladoAt = OffsetDateTime.now();
        this.motivoAnulacion = motivo;
    }

    /** Si muestra el IVA discriminado: depende de la condición del emisor, copiada al emitir. */
    public boolean discriminaIva() {
        return emisorCondicionIva.discriminaIva();
    }

    /** "Documento no válido como factura" en los X; null en los fiscales. */
    public String leyenda() {
        return letra == LetraComprobante.X ? ReglasComprobante.LEYENDA_NO_FISCAL : null;
    }

    /** Punto de venta y número, como se imprimen: 00001-00000023. */
    public String numeroCompleto() {
        return "%05d-%08d".formatted(puntoVenta.getNumero(), numero);
    }

    public TipoComprobante getTipo() {
        return tipo;
    }

    public LetraComprobante getLetra() {
        return letra;
    }

    public PuntoVenta getPuntoVenta() {
        return puntoVenta;
    }

    public Integer getNumero() {
        return numero;
    }

    public String getEmisorRazonSocial() {
        return emisorRazonSocial;
    }

    public String getEmisorCuit() {
        return emisorCuit;
    }

    public CondicionIva getEmisorCondicionIva() {
        return emisorCondicionIva;
    }

    public String getEmisorDomicilioFiscal() {
        return emisorDomicilioFiscal;
    }

    public String getEmisorNombre() {
        return emisorNombre;
    }

    public String getEmisorDireccion() {
        return emisorDireccion;
    }

    public Cliente getCliente() {
        return cliente;
    }

    public String getReceptorRazonSocial() {
        return receptorRazonSocial;
    }

    public TipoDocumento getReceptorDocumentoTipo() {
        return receptorDocumentoTipo;
    }

    public String getReceptorDocumento() {
        return receptorDocumento;
    }

    public CondicionIva getReceptorCondicionIva() {
        return receptorCondicionIva;
    }

    public String getReceptorDomicilio() {
        return receptorDomicilio;
    }

    public Usuario getUsuario() {
        return usuario;
    }

    public OffsetDateTime getFecha() {
        return fecha;
    }

    public LocalDate getFechaVencimiento() {
        return fechaVencimiento;
    }

    public EstadoComprobante getEstado() {
        return estado;
    }

    public Usuario getAnuladoPor() {
        return anuladoPor;
    }

    public OffsetDateTime getAnuladoAt() {
        return anuladoAt;
    }

    public String getMotivoAnulacion() {
        return motivoAnulacion;
    }

    public Comprobante getAsociado() {
        return asociado;
    }

    public boolean isPreciosConIva() {
        return preciosConIva;
    }

    public BigDecimal getNeto() {
        return neto;
    }

    public BigDecimal getIva() {
        return iva;
    }

    public BigDecimal getTotal() {
        return total;
    }

    public String getObservaciones() {
        return observaciones;
    }

    public List<DetalleComprobante> getRenglones() {
        return renglones;
    }
}
