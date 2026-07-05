package com.opencode.facturas.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
public class StoreNameMapper {

    private final Map<String, String> mappings;

    @Autowired
    public StoreNameMapper(ObjectMapper objectMapper,
                           @Value("classpath:store-mappings.json") Resource mappingsResource) {
        this.mappings = loadMappings(objectMapper, mappingsResource);
    }

    public StoreNameMapper(Map<String, String> mappings) {
        this.mappings = mappings;
    }

    public static StoreNameMapper empty() {
        return new StoreNameMapper(Map.of());
    }

    public String resolve(List<String> lines, String detectedStoreName) {
        for (String line : lines) {
            String mapped = findMapping(line);
            if (mapped != null) {
                return mapped;
            }
        }

        String mappedDetected = findMapping(detectedStoreName);
        return mappedDetected != null ? mappedDetected : detectedStoreName;
    }

    private String findMapping(String candidate) {
        String normalizedCandidate = normalize(candidate);
        if (normalizedCandidate.isBlank()) {
            return null;
        }
        String compactCandidate = normalizedCandidate.replace(" ", "");

        if (normalizedCandidate.contains("zou wenguo")
                || normalizedCandidate.contains("zou wenguc")
                || compactCandidate.contains("zouwenguo")
                || compactCandidate.contains("zouwenguc")) {
            return "Los Tres Corazones";
        }
        if (normalizedCandidate.contains("los tres corazones")
                || normalizedCandidate.contains("tres corazones")
                || compactCandidate.contains("lostrescorazones")
                || compactCandidate.contains("trescorazones")) {
            return "Los Tres Corazones";
        }

        for (Map.Entry<String, String> entry : mappings.entrySet()) {
            String alias = entry.getKey();
            String compactAlias = alias.replace(" ", "");
            if (normalizedCandidate.equals(alias)
                    || normalizedCandidate.contains(alias)
                    || compactCandidate.equals(compactAlias)
                    || compactCandidate.contains(compactAlias)) {
                return entry.getValue();
            }
        }

        return null;
    }

    private Map<String, String> loadMappings(ObjectMapper objectMapper, Resource mappingsResource) {
        try (InputStream inputStream = mappingsResource.getInputStream()) {
            Map<String, String> rawMappings = objectMapper.readValue(inputStream, new TypeReference<>() {});
            Map<String, String> normalizedMappings = new LinkedHashMap<>();

            for (Map.Entry<String, String> entry : rawMappings.entrySet()) {
                normalizedMappings.put(normalize(entry.getKey()), entry.getValue());
            }

            return normalizedMappings;
        } catch (IOException ex) {
            throw new IllegalStateException("No se pudo leer store-mappings.json", ex);
        }
    }

    private String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .replaceAll("[^a-z0-9 ]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
