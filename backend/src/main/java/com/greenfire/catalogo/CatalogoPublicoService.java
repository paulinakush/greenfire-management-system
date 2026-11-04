package com.greenfire.catalogo;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.greenfire.ambitos.Ambito;
import com.greenfire.ambitos.AmbitoRepository;
import com.greenfire.ambitos.TipoAmbito;
import com.greenfire.catalogo.dto.ArticuloPublicoResponse;
import com.greenfire.catalogo.dto.CatalogoPublicoResponse;
import com.greenfire.catalogo.dto.CategoriaPublicaResponse;
import com.greenfire.comun.NoEncontradoException;
import com.greenfire.comun.SolicitudInvalidaException;
import com.greenfire.precios.CalculoPrecio;
import com.greenfire.precios.ListaPreciosItem;
import com.greenfire.precios.ListaPreciosItemRepository;
import com.greenfire.stock.StockArticuloRepository;

/**
 * Catálogo del sitio público, sin login y de solo lectura. Muestra los artículos activos, visibles en la web, de
 * tipo producto terminado o reventa y con categoría activa, que tienen precio en la lista predeterminada de un
 * local visible en la web. El visitante elige el local; si no elige, se usa el local central.
 * <p>
 * El precio es el que paga el consumidor: si la entidad del local discrimina IVA y la lista es sin IVA, se le suma
 * el IVA; si no discrimina, el de la lista es el precio final. La disponibilidad es solo sí o no: si la suma de las
 * ubicaciones activas del local es mayor que cero.
 */
@Service
@Transactional(readOnly = true)
public class CatalogoPublicoService {

    public static final int TAMANO_MAXIMO = 50;
    public static final int TAMANO_POR_DEFECTO = 20;
    public static final int LARGO_MAXIMO_BUSQUEDA = 100;
    private static final Set<TipoArticulo> TIPOS_PUBLICOS = EnumSet.of(TipoArticulo.producto_terminado,
            TipoArticulo.reventa);
    /** Igual para un artículo que no existe, que está oculto o que no se vende en el local: no revela qué hay. */
    private static final String ARTICULO_NO_EXISTE = "No existe el artículo indicado.";

    private final AmbitoRepository ambitos;
    private final CategoriaRepository categorias;
    private final ListaPreciosItemRepository items;
    private final StockArticuloRepository stock;

    public CatalogoPublicoService(AmbitoRepository ambitos, CategoriaRepository categorias,
            ListaPreciosItemRepository items, StockArticuloRepository stock) {
        this.ambitos = ambitos;
        this.categorias = categorias;
        this.items = items;
        this.stock = stock;
    }

    public List<CategoriaPublicaResponse> categorias() {
        return categorias.findByActivoTrueOrderByNombre().stream().map(CategoriaPublicaResponse::de).toList();
    }

    public CatalogoPublicoResponse catalogo(Long localId, Long categoriaId, String q, int pagina, int tamano) {
        Ambito local = local(localId);
        int paginaReal = Math.max(pagina, 0);
        int tamanoReal = Math.min(Math.max(tamano, 1), TAMANO_MAXIMO);
        Page<ListaPreciosItem> resultado = items.catalogoPublico(local.getId(), TIPOS_PUBLICOS, categoriaId,
                patron(q), PageRequest.of(paginaReal, tamanoReal));
        List<ArticuloPublicoResponse> articulos = respuestas(local, resultado.getContent());
        return new CatalogoPublicoResponse(local.getId(), local.getNombre(), articulos, paginaReal, tamanoReal,
                resultado.getTotalElements());
    }

    public ArticuloPublicoResponse articulo(Long articuloId, Long localId) {
        Ambito local = local(localId);
        ListaPreciosItem item = items.articuloPublico(local.getId(), TIPOS_PUBLICOS, articuloId)
                .orElseThrow(() -> new NoEncontradoException(ARTICULO_NO_EXISTE));
        return respuestas(local, List.of(item)).get(0);
    }

    /** El local elegido, si se muestra en la web; sin elegir, el central. */
    private Ambito local(Long localId) {
        if (localId == null) {
            return ambitos.findByEsCentralTrue().orElseThrow(() -> new SolicitudInvalidaException(
                    "Elegí un local: todavía no hay un local central."));
        }
        return ambitos.findByIdAndTipoAndVisibleWebTrueAndActivoTrue(localId, TipoAmbito.local)
                .orElseThrow(() -> new NoEncontradoException("No existe el local indicado."));
    }

    private List<ArticuloPublicoResponse> respuestas(Ambito local, List<ListaPreciosItem> pagina) {
        if (pagina.isEmpty()) {
            return List.of();
        }
        boolean discrimina = local.getEntidadFiscal().getCondicionIva().discriminaIva();
        Set<Long> conStock = new HashSet<>(stock.conStock(local.getId(),
                pagina.stream().map(i -> i.getArticulo().getId()).toList()));
        return pagina.stream().map(i -> {
            Articulo a = i.getArticulo();
            BigDecimal precio = CalculoPrecio.precioFinal(i.getPrecio(), discrimina, i.getLista().isPreciosConIva(),
                    a.getAlicuotaIva());
            return ArticuloPublicoResponse.de(a, precio, conStock.contains(a.getId()));
        }).toList();
    }

    /** Texto de búsqueda como patrón LIKE, sin comodines del visitante; sin texto, coincide con todo. */
    private static String patron(String q) {
        String texto = q == null ? "" : q.strip();
        if (texto.length() > LARGO_MAXIMO_BUSQUEDA) {
            throw new SolicitudInvalidaException(
                    "El texto de búsqueda admite hasta " + LARGO_MAXIMO_BUSQUEDA + " caracteres.");
        }
        String escapado = texto.toLowerCase().replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return "%" + escapado + "%";
    }
}
