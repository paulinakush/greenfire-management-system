package com.greenfire.precios;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.greenfire.ambitos.Ambito;
import com.greenfire.auditoria.Auditor;
import com.greenfire.catalogo.Articulo;
import com.greenfire.comun.ReglaNegocioException;
import com.greenfire.precios.dto.ActualizacionMasivaRequest;
import com.greenfire.precios.dto.ActualizacionMasivaResponse;
import com.greenfire.seguridad.Sesion;

/**
 * Cambia en un porcentaje los precios de una lista, opcionalmente solo los de una categoría o un tipo. Todo o
 * nada: si algún precio quedaría en cero, no se aplica ninguno. Cada precio que cambia deja su fila en el
 * historial, y la operación queda en la auditoría como un solo registro.
 */
@Service
@Transactional
public class ActualizacionMasivaService {

    private static final BigDecimal CIEN = BigDecimal.valueOf(100);
    private static final BigDecimal CENTAVO = new BigDecimal("0.01");

    private final ListaPreciosService listas;
    private final ListaPreciosItemRepository items;
    private final Auditor auditor;

    public ActualizacionMasivaService(ListaPreciosService listas, ListaPreciosItemRepository items, Auditor auditor) {
        this.listas = listas;
        this.items = items;
        this.auditor = auditor;
    }

    public ActualizacionMasivaResponse aplicar(Long id, ActualizacionMasivaRequest req) {
        Sesion sesion = listas.exigirDueno();
        Ambito ambito = listas.ambitoDe(sesion);
        ListaPrecios lista = listas.buscarActiva(id, ambito);
        BigDecimal redondeo = req.redondeo() == null ? CENTAVO : req.redondeo();
        BigDecimal factor = BigDecimal.ONE.add(req.porcentaje().divide(CIEN, 4, RoundingMode.UNNECESSARY));

        List<ListaPreciosItem> elegidos = items.listar(lista.getId()).stream()
                .filter(i -> req.tipo() == null || i.getArticulo().getTipo() == req.tipo())
                .filter(i -> req.categoriaId() == null || (i.getArticulo().getCategoria() != null
                        && i.getArticulo().getCategoria().getId().equals(req.categoriaId())))
                .toList();

        int actualizados = 0;
        for (ListaPreciosItem item : elegidos) {
            BigDecimal anterior = item.getPrecio();
            BigDecimal nuevo = CalculoPrecio.redondear(anterior.multiply(factor), redondeo);
            Articulo a = item.getArticulo();
            if (nuevo.signum() <= 0) {
                throw new ReglaNegocioException("El precio de '" + a.getNombre()
                        + "' quedaría en cero con ese porcentaje y redondeo. No se cambió ningún precio.");
            }
            if (nuevo.compareTo(anterior) != 0) {
                item.setPrecio(nuevo);
                listas.guardarHistorial(sesion, lista, a, anterior, nuevo);
                actualizados++;
            }
        }

        Map<String, Object> detalle = new LinkedHashMap<>();
        detalle.put("porcentaje", req.porcentaje());
        detalle.put("categoriaId", req.categoriaId());
        detalle.put("tipo", req.tipo());
        detalle.put("redondeo", redondeo);
        detalle.put("actualizados", actualizados);
        auditor.registrar("actualizacion_masiva", ListaPreciosService.ENTIDAD, lista.getId(),
                List.of(ambito.getId()), detalle);
        return new ActualizacionMasivaResponse(actualizados);
    }
}
