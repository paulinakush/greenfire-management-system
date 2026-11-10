package com.greenfire.usuarios.dto;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.ReportAsSingleViolation;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/** Nombre de usuario: de 3 a 30 letras minúsculas, números, puntos o guiones bajos (como el CHECK de la base). */
@NotNull
@Pattern(regexp = "[a-z0-9._]{3,30}")
@ReportAsSingleViolation
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = {})
public @interface UsuarioValido {

    String message() default "El usuario debe tener entre 3 y 30 caracteres: letras minúsculas, números, punto o guion bajo.";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
