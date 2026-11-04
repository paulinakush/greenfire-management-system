package com.greenfire.importacion;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.greenfire.comun.SolicitudInvalidaException;

/**
 * Lee una planilla CSV en UTF-8 (con o sin BOM), separada por coma o por punto y coma: el separador se toma de la
 * fila de encabezado. Admite valores entre comillas, con comillas dobles adentro y saltos de línea. La primera fila
 * es el encabezado y las filas se numeran como en la planilla (el encabezado es la fila 1). Las filas vacías se
 * saltean.
 * <p>
 * Un problema con el archivo entero (tamaño, codificación, encabezado, cantidad de filas) responde 400, o 413 si es
 * demasiado grande; uno de una fila es un error de esa fila, que informa el servicio.
 */
public final class LectorCsv {

    public static final int TAMANO_MAXIMO = 1024 * 1024;
    public static final int FILAS_MAXIMAS = 2000;

    /** Una fila de datos: su número en la planilla y el valor de cada columna, sin espacios en los extremos. */
    public record Fila(int numero, Map<String, String> valores, boolean columnasDeMas) {

        /** El valor de la columna, o null si está vacío o la columna no está en el archivo. */
        public String valor(String columna) {
            String v = valores.get(columna);
            return v == null || v.isEmpty() ? null : v;
        }
    }

    public record Archivo(char separador, List<String> columnas, List<Fila> filas) {
    }

    private LectorCsv() {
    }

    public static Archivo leer(byte[] contenido, Set<String> obligatorias, Set<String> opcionales) {
        if (contenido.length > TAMANO_MAXIMO) {
            throw new ArchivoDemasiadoGrandeException("El archivo supera el máximo de " + TAMANO_MAXIMO / 1024
                    + " KB.");
        }
        String texto = decodificar(contenido);
        if (texto.startsWith("﻿")) {
            texto = texto.substring(1);
        }
        char separador = separador(texto);
        List<List<String>> registros = registros(texto, separador);
        if (registros.isEmpty() || registros.get(0).stream().allMatch(String::isBlank)) {
            throw new SolicitudInvalidaException("El archivo está vacío: falta la fila de encabezado.");
        }

        List<String> columnas = encabezado(registros.get(0), obligatorias, opcionales);
        List<Fila> filas = new ArrayList<>();
        for (int i = 1; i < registros.size(); i++) {
            List<String> registro = registros.get(i);
            if (registro.stream().allMatch(String::isBlank)) {
                continue;
            }
            if (filas.size() == FILAS_MAXIMAS) {
                throw new SolicitudInvalidaException("El archivo supera el máximo de " + FILAS_MAXIMAS + " filas.");
            }
            Map<String, String> valores = new LinkedHashMap<>();
            for (int c = 0; c < columnas.size(); c++) {
                valores.put(columnas.get(c), c < registro.size() ? registro.get(c).strip() : "");
            }
            boolean deMas = registro.subList(Math.min(columnas.size(), registro.size()), registro.size()).stream()
                    .anyMatch(v -> !v.isBlank());
            filas.add(new Fila(i + 1, valores, deMas));
        }
        if (filas.isEmpty()) {
            throw new SolicitudInvalidaException("El archivo no tiene filas para importar.");
        }
        return new Archivo(separador, columnas, filas);
    }

    /** Nombre de columna o valor de una lista cerrada: sin tildes, en minúsculas y con guion bajo por espacio. */
    public static String normalizar(String texto) {
        String sinTildes = Normalizer.normalize(texto.strip(), Normalizer.Form.NFD).replaceAll("\\p{M}", "");
        return sinTildes.toLowerCase(Locale.ROOT).replaceAll("\\s+", "_");
    }

    private static String decodificar(byte[] contenido) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(contenido)).toString();
        } catch (CharacterCodingException e) {
            throw new SolicitudInvalidaException(
                    "El archivo tiene que estar en UTF-8. Desde la planilla, guardalo como \"CSV UTF-8\".");
        }
    }

    /** El que más aparece en la primera línea, fuera de comillas; si no aparece ninguno, coma. */
    private static char separador(String texto) {
        int comas = 0;
        int puntoYComas = 0;
        boolean entreComillas = false;
        for (int i = 0; i < texto.length(); i++) {
            char c = texto.charAt(i);
            if (c == '"') {
                entreComillas = !entreComillas;
            } else if (!entreComillas && (c == '\n' || c == '\r')) {
                break;
            } else if (!entreComillas && c == ',') {
                comas++;
            } else if (!entreComillas && c == ';') {
                puntoYComas++;
            }
        }
        return puntoYComas > comas ? ';' : ',';
    }

    private static List<List<String>> registros(String texto, char separador) {
        List<List<String>> registros = new ArrayList<>();
        List<String> actual = new ArrayList<>();
        StringBuilder valor = new StringBuilder();
        boolean entreComillas = false;
        int i = 0;
        while (i < texto.length()) {
            char c = texto.charAt(i);
            if (entreComillas) {
                if (c == '"' && i + 1 < texto.length() && texto.charAt(i + 1) == '"') {
                    valor.append('"');
                    i++;
                } else if (c == '"') {
                    entreComillas = false;
                } else {
                    valor.append(c);
                }
            } else if (c == '"') {
                entreComillas = true;
            } else if (c == separador) {
                actual.add(valor.toString());
                valor.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < texto.length() && texto.charAt(i + 1) == '\n') {
                    i++;
                }
                actual.add(valor.toString());
                valor.setLength(0);
                registros.add(actual);
                actual = new ArrayList<>();
            } else {
                valor.append(c);
            }
            i++;
        }
        if (entreComillas) {
            throw new SolicitudInvalidaException("El archivo tiene comillas sin cerrar.");
        }
        if (valor.length() > 0 || !actual.isEmpty()) {
            actual.add(valor.toString());
            registros.add(actual);
        }
        return registros;
    }

    private static List<String> encabezado(List<String> registro, Set<String> obligatorias, Set<String> opcionales) {
        List<String> columnas = new ArrayList<>();
        for (String c : registro) {
            String nombre = normalizar(c);
            if (nombre.isEmpty()) {
                throw new SolicitudInvalidaException("El encabezado tiene una columna sin nombre.");
            }
            if (!obligatorias.contains(nombre) && !opcionales.contains(nombre)) {
                throw new SolicitudInvalidaException("La columna '" + c.strip() + "' no corresponde. Las columnas son: "
                        + String.join(", ", ordenadas(obligatorias, opcionales)) + ".");
            }
            if (columnas.contains(nombre)) {
                throw new SolicitudInvalidaException("La columna '" + nombre + "' está repetida.");
            }
            columnas.add(nombre);
        }
        for (String obligatoria : obligatorias) {
            if (!columnas.contains(obligatoria)) {
                throw new SolicitudInvalidaException("Falta la columna '" + obligatoria + "'.");
            }
        }
        return columnas;
    }

    private static List<String> ordenadas(Set<String> obligatorias, Set<String> opcionales) {
        List<String> todas = new ArrayList<>(obligatorias.stream().sorted().toList());
        todas.addAll(opcionales.stream().sorted().toList());
        return todas;
    }
}
