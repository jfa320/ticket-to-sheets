package com.opencode.facturas.service;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.opencode.facturas.controller.CorrectionController;
import com.opencode.facturas.model.CorrectionsRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import java.nio.file.Path;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class ReceiptLoggingTest {
    @TempDir Path directory;
    @Test void normalLogsDoNotContainStoreDateProductOrPurchaseTotal() {
        var appender = new ListAppender<ILoggingEvent>(); appender.start();
        Logger parserLogger = (Logger) LoggerFactory.getLogger(ReceiptParserService.class);
        Logger correctionsLogger = (Logger) LoggerFactory.getLogger(CorrectionController.class);
        parserLogger.addAppender(appender); correctionsLogger.addAppender(appender);
        try {
            var mapper = new ObjectMapper();
            var brands = new BrandCatalog(mapper, directory.resolve("brands.json"));
            var memory = new CorrectionMemory(mapper, directory.resolve("memory.json"));
            new ReceiptParserService(StoreNameMapper.empty(), brands, memory)
                    .parse("Comercio Ficticio Reservado\nFecha 13/10/2026\nLECHE ENTERA 1234,56");
            new CorrectionController(memory, brands).learn(new CorrectionsRequest("Comercio Ficticio Reservado",
                    List.of(new CorrectionsRequest.Correction("leche entera", "Leche Entera", "Genérico", "Otros"))));
            assertThat(appender.list).isNotEmpty();
            String normalLogs = appender.list.stream().filter(e -> e.getLevel().isGreaterOrEqual(Level.INFO))
                    .map(ILoggingEvent::getFormattedMessage).collect(java.util.stream.Collectors.joining("\n"));
            assertThat(normalLogs).doesNotContain("Ficticio", "13/10/2026", "Leche", "1234,56");
        } finally { parserLogger.detachAppender(appender); correctionsLogger.detachAppender(appender); appender.stop(); }
    }
}
