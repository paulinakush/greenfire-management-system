package com.greenfire.comun;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.ErrorResponse;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import com.greenfire.auditoria.AuditoriaException;
import com.greenfire.importacion.ArchivoDemasiadoGrandeException;
import com.greenfire.importacion.LectorCsv;

@RestControllerAdvice
public class ManejadorErrores {

    private static final Logger log = LoggerFactory.getLogger(ManejadorErrores.class);

    @ExceptionHandler(NoAutenticadoException.class)
    ResponseEntity<ApiError> noAutenticado(NoAutenticadoException e) {
        return respuesta(HttpStatus.UNAUTHORIZED, e.getMessage(), null);
    }

    @ExceptionHandler(AccesoDenegadoException.class)
    ResponseEntity<ApiError> accesoDenegado(AccesoDenegadoException e) {
        return respuesta(HttpStatus.FORBIDDEN, e.getMessage(), null);
    }

    @ExceptionHandler(NoEncontradoException.class)
    ResponseEntity<ApiError> noEncontrado(NoEncontradoException e) {
        return respuesta(HttpStatus.NOT_FOUND, e.getMessage(), null);
    }

    @ExceptionHandler(SolicitudInvalidaException.class)
    ResponseEntity<ApiError> solicitudInvalida(SolicitudInvalidaException e) {
        return respuesta(HttpStatus.BAD_REQUEST, e.getMessage(), null);
    }

    @ExceptionHandler(ArchivoDemasiadoGrandeException.class)
    ResponseEntity<ApiError> archivoDemasiadoGrande(ArchivoDemasiadoGrandeException e) {
        return respuesta(HttpStatus.PAYLOAD_TOO_LARGE, e.getMessage(), null);
    }

    /** Un archivo que supera incluso el límite de multipart de Spring (ver application.yml). */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<ApiError> subidaDemasiadoGrande(MaxUploadSizeExceededException e) {
        return respuesta(HttpStatus.PAYLOAD_TOO_LARGE,
                "El archivo supera el máximo de " + LectorCsv.TAMANO_MAXIMO / 1024 + " KB.", null);
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    ResponseEntity<ApiError> faltaArchivo(MissingServletRequestPartException e) {
        return respuesta(HttpStatus.BAD_REQUEST, "Falta el archivo '" + e.getRequestPartName() + "'.", null);
    }

    @ExceptionHandler(ReglaNegocioException.class)
    ResponseEntity<ApiError> reglaNegocio(ReglaNegocioException e) {
        return respuesta(HttpStatus.CONFLICT, e.getMessage(), null);
    }

    /**
     * Un movimiento que dejaría el stock negativo y que la validación previa no frenó (lo frena el CHECK
     * stock_articulos_sin_negativo, ver V6) responde igual que {@code StockInsuficienteException}. Dos conteos
     * abiertos a la vez en una ubicación los frena el índice uq_conteos_stock_abierto (V7). El local central lo
     * cuidan ambitos_central_valido y uq_ambitos_central (V8), y la recepción de una entrega,
     * detalle_entregas_recibida_no_mayor (V9).
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> integridad(DataIntegrityViolationException e) {
        String causa = e.getMostSpecificCause().getMessage();
        if (causa != null && causa.contains("stock_articulos_sin_negativo")) {
            return respuesta(HttpStatus.CONFLICT, "Stock insuficiente.", null);
        }
        if (causa != null && causa.contains("uq_conteos_stock_abierto")) {
            return respuesta(HttpStatus.CONFLICT, "Ya hay un conteo abierto en esa ubicación.", null);
        }
        if (causa != null && causa.contains("detalle_entregas_recibida_no_mayor")) {
            return respuesta(HttpStatus.CONFLICT, "La cantidad recibida no puede ser mayor que la enviada.", null);
        }
        if (causa != null && causa.contains("ambitos_central_valido")) {
            return respuesta(HttpStatus.CONFLICT,
                    "El local central tiene que ser un local activo y visible en la web.", null);
        }
        if (causa != null && causa.contains("uq_ambitos_central")) {
            return respuesta(HttpStatus.CONFLICT, "Ya hay otro local central.", null);
        }
        return respuesta(HttpStatus.CONFLICT, "La operación contradice datos ya registrados.", null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validacion(MethodArgumentNotValidException e) {
        Map<String, String> campos = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(f -> campos.putIfAbsent(f.getField(), f.getDefaultMessage()));
        return respuesta(HttpStatus.BAD_REQUEST, "Hay datos inválidos.", campos);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> cuerpoInvalido(HttpMessageNotReadableException e) {
        return respuesta(HttpStatus.BAD_REQUEST, "El cuerpo de la solicitud no es válido.", null);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> parametroInvalido(MethodArgumentTypeMismatchException e) {
        return respuesta(HttpStatus.BAD_REQUEST, "El parámetro '" + e.getName() + "' no es válido.", null);
    }

    @ExceptionHandler(AuditoriaException.class)
    ResponseEntity<ApiError> auditoria(AuditoriaException e) {
        log.error("Falló el registro de auditoría; la operación se revirtió.", e);
        return respuesta(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage(), null);
    }

    /** Cualquier otro error: 500 sin detalles internos. Las excepciones de Spring MVC conservan su código. */
    @ExceptionHandler(RuntimeException.class)
    ResponseEntity<ApiError> inesperado(RuntimeException e) {
        if (e instanceof ErrorResponse conocido) {
            HttpStatusCode codigo = conocido.getStatusCode();
            return respuesta(HttpStatus.valueOf(codigo.value()), conocido.getBody().getDetail(), null);
        }
        log.error("Error no controlado", e);
        return respuesta(HttpStatus.INTERNAL_SERVER_ERROR, "Ocurrió un error inesperado.", null);
    }

    private ResponseEntity<ApiError> respuesta(HttpStatus status, String mensaje, Map<String, String> campos) {
        return ResponseEntity.status(status).body(new ApiError(status.value(), status.name(), mensaje, campos));
    }
}
