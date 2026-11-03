package com.greenfire.comun;

import java.time.Duration;

import org.springframework.http.CacheControl;

/** Caché de las respuestas del sitio público: corto, así un cambio de precio o de stock se ve en menos de un minuto. */
public final class CachePublico {

    public static final CacheControl CORTO = CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic();

    private CachePublico() {
    }
}
