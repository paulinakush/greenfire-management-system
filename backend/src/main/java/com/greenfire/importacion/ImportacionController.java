package com.greenfire.importacion;

import java.io.IOException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.greenfire.importacion.dto.ResultadoImportacion;

/**
 * Importación CSV de artículos y de precios, en dos pasos: la vista previa no guarda nada y la confirmación aplica
 * el archivo todo o nada. El archivo va como multipart, en el campo {@code archivo}. Una confirmación con errores
 * responde 409 con el detalle de cada fila.
 */
@RestController
@RequestMapping("/api/importacion")
public class ImportacionController {

    private final ImportacionArticulosService articulos;
    private final ImportacionPreciosService precios;

    public ImportacionController(ImportacionArticulosService articulos, ImportacionPreciosService precios) {
        this.articulos = articulos;
        this.precios = precios;
    }

    @PostMapping("/articulos/vista-previa")
    public ResultadoImportacion vistaPreviaArticulos(@RequestParam("archivo") MultipartFile archivo)
            throws IOException {
        return articulos.vistaPrevia(nombre(archivo), archivo.getBytes());
    }

    @PostMapping("/articulos")
    public ResultadoImportacion importarArticulos(@RequestParam("archivo") MultipartFile archivo) throws IOException {
        return articulos.aplicar(nombre(archivo), archivo.getBytes());
    }

    @PostMapping("/precios/vista-previa")
    public ResultadoImportacion vistaPreviaPrecios(@RequestParam Long listaId,
            @RequestParam("archivo") MultipartFile archivo) throws IOException {
        return precios.vistaPrevia(listaId, nombre(archivo), archivo.getBytes());
    }

    @PostMapping("/precios")
    public ResultadoImportacion importarPrecios(@RequestParam Long listaId,
            @RequestParam("archivo") MultipartFile archivo) throws IOException {
        return precios.aplicar(listaId, nombre(archivo), archivo.getBytes());
    }

    @ExceptionHandler(ImportacionConErroresException.class)
    ResponseEntity<ResultadoImportacion> conErrores(ImportacionConErroresException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getResultado());
    }

    /** Solo el nombre, sin la carpeta que algunos navegadores mandan, y con un largo razonable. */
    private static String nombre(MultipartFile archivo) {
        String original = archivo.getOriginalFilename();
        if (original == null || original.isBlank()) {
            return "(sin nombre)";
        }
        String nombre = original.substring(Math.max(original.lastIndexOf('/'), original.lastIndexOf('\\')) + 1);
        return nombre.length() > 200 ? nombre.substring(0, 200) : nombre;
    }
}
