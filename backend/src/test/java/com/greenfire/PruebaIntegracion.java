package com.greenfire;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Transactional;

/**
 * Test de integración contra PostgreSQL (Testcontainers). Todos los tests comparten el mismo contexto
 * y contenedor; cada test corre en una transacción que se revierte al terminar.
 * Las variables que podrían venir del .env del desarrollador se fijan acá, para que no influyan.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = {
        "greenfire.admin-inicial.nombre=",
        "greenfire.admin-inicial.email=",
        "greenfire.admin-inicial.password=",
        "greenfire.recuperacion.log-enlace=false",
        "server.servlet.session.cookie.secure=false"})
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, Datos.class})
@Transactional
@RecordApplicationEvents
public @interface PruebaIntegracion {
}
