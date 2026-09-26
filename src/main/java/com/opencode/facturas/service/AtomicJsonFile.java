package com.opencode.facturas.service;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

final class AtomicJsonFile {

    private static final Logger log = LoggerFactory.getLogger(AtomicJsonFile.class);

    private AtomicJsonFile() {
    }

    static <T> T loadOrRecover(ObjectMapper objectMapper, Path path, JavaType valueType, T emptyValue) {
        Path target = path.toAbsolutePath().normalize();
        Path backup = backupPath(target);
        if (!Files.exists(target) && !Files.exists(backup)) {
            return emptyValue;
        }

        if (Files.exists(target)) {
            try {
                return read(objectMapper, target, valueType);
            } catch (IOException primaryFailure) {
                return recover(objectMapper, target, backup, valueType, primaryFailure);
            }
        }

        return recover(objectMapper, target, backup, valueType, null);
    }

    static void save(ObjectMapper objectMapper, Path path, Object value) throws IOException {
        Path target = path.toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        Path stagedFile = createTemporaryFile(target, ".tmp");
        Path stagedBackup = null;
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(stagedFile.toFile(), value);

            if (Files.exists(target)) {
                stagedBackup = createTemporaryFile(target, ".bak.tmp");
                Files.copy(target, stagedBackup, StandardCopyOption.REPLACE_EXISTING);
                moveReplacing(stagedBackup, backupPath(target));
                stagedBackup = null;
            }

            moveReplacing(stagedFile, target);
            stagedFile = null;
        } finally {
            deleteTemporaryFile(stagedFile);
            deleteTemporaryFile(stagedBackup);
        }
    }

    static Path backupPath(Path path) {
        return path.resolveSibling(path.getFileName() + ".bak");
    }

    private static <T> T recover(ObjectMapper objectMapper, Path target, Path backup,
                                 JavaType valueType, IOException primaryFailure) {
        if (!Files.exists(backup)) {
            throw unreadableFile(target, primaryFailure, null);
        }

        final T recovered;
        try {
            recovered = read(objectMapper, backup, valueType);
        } catch (IOException backupFailure) {
            throw unreadableFile(target, primaryFailure, backupFailure);
        }

        try {
            restoreBackup(backup, target);
        } catch (IOException restoreFailure) {
            throw unreadableFile(target, primaryFailure, restoreFailure);
        }

        log.warn("Se recuperó {} desde su copia de respaldo después de detectar un JSON ilegible", target);
        return recovered;
    }

    private static <T> T read(ObjectMapper objectMapper, Path path, JavaType valueType) throws IOException {
        return objectMapper.readerFor(valueType).readValue(path.toFile());
    }

    private static void restoreBackup(Path backup, Path target) throws IOException {
        Path stagedFile = createTemporaryFile(target, ".restore.tmp");
        try {
            Files.copy(backup, stagedFile, StandardCopyOption.REPLACE_EXISTING);
            moveReplacing(stagedFile, target);
            stagedFile = null;
        } finally {
            deleteTemporaryFile(stagedFile);
        }
    }

    private static Path createTemporaryFile(Path target, String suffix) throws IOException {
        Path parent = target.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        return Files.createTempFile(parent, "." + target.getFileName() + ".", suffix);
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ex) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void deleteTemporaryFile(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ex) {
            log.warn("No se pudo limpiar un archivo temporal JSON: {}", path);
        }
    }

    private static IllegalStateException unreadableFile(Path path, IOException primaryFailure,
                                                        IOException recoveryFailure) {
        IllegalStateException exception = new IllegalStateException(
                "No se pudo leer ni recuperar " + path + ". Verificá el archivo JSON y su copia .bak.",
                primaryFailure != null ? primaryFailure : recoveryFailure
        );
        if (primaryFailure != null && recoveryFailure != null) {
            exception.addSuppressed(recoveryFailure);
        }
        return exception;
    }
}
