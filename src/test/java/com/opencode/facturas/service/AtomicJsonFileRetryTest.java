package com.opencode.facturas.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class AtomicJsonFileRetryTest {
    @TempDir Path directory;
    @Test void transientSharingViolationsKeepThePreviousFileUntilReplacementSucceeds() throws Exception {
        var source=directory.resolve("stage.json");var target=directory.resolve("data.json");
        Files.writeString(source,"new");Files.writeString(target,"old");var calls=new AtomicInteger();var delays=new ArrayList<Long>();
        AtomicJsonFile.retryMove(()-> {
            if(calls.incrementAndGet()<3)throw new AccessDeniedException(target.toString());
            Files.move(source,target,StandardCopyOption.REPLACE_EXISTING);
        }, millis->{ assertThat(target).hasContent("old");delays.add(millis); });
        assertThat(Files.readString(target)).isEqualTo("new");assertThat(delays).containsExactly(25L,50L);
    }
    @Test void aPermanentDenialIsBoundedAndKeepsTheOriginalError() {
        var calls=new AtomicInteger();var error=new AccessDeniedException("data.json");
        assertThatThrownBy(()->AtomicJsonFile.retryMove(()->{calls.incrementAndGet();throw error;},millis->{})).isSameAs(error);
        assertThat(calls.get()).isEqualTo(4);
    }
    @Test void unrelatedIoFailuresAreNotRetried() {
        var calls=new AtomicInteger();var error=new java.io.IOException("disk error");
        assertThatThrownBy(()->AtomicJsonFile.retryMove(()->{calls.incrementAndGet();throw error;},millis->{throw new AssertionError();})).isSameAs(error);
        assertThat(calls.get()).isEqualTo(1);
    }
    @Test void interruptionStopsRetriesAndPreservesTheInterruptFlag() {
        try {
            assertThatThrownBy(()->AtomicJsonFile.retryMove(()->{throw new AccessDeniedException("data.json");},
                millis->{throw new InterruptedException();})).isInstanceOf(java.io.InterruptedIOException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally { Thread.interrupted(); }
    }
}
