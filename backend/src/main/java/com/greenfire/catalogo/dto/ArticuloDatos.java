package com.greenfire.catalogo.dto;

import java.math.BigDecimal;
import java.util.stream.Stream;

import com.greenfire.catalogo.UnidadMedida;

/** Campos editables de un artículo, comunes al alta y a la edición. */
public interface ArticuloDatos {

    /** Alícuotas de IVA válidas, las mismas que el CHECK de la base. */
    String ALICUOTAS = "0, 10.5, 21 o 27";

    /** Ruta dentro de assets/productos/, sin subir de carpeta. */
    String RUTA_IMAGEN = "assets/productos/(?!.*\\.\\.).+";

    String nombre();

    String descripcion();

    Long categoriaId();

    String marca();

    UnidadMedida unidadMedida();

    String imagen();

    BigDecimal alicuotaIva();

    boolean visibleWeb();

    static boolean alicuotaValida(BigDecimal alicuota) {
        return alicuota == null || Stream.of("0", "10.5", "21", "27")
                .anyMatch(v -> new BigDecimal(v).compareTo(alicuota) == 0);
    }
}
