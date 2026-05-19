package com.systemdesign.objectstorage.controller;

import com.systemdesign.objectstorage.service.DeleteObjectService;
import com.systemdesign.objectstorage.service.GetObjectService;
import com.systemdesign.objectstorage.service.PutObjectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests {@link StorageGatewayController} using hand-rolled service stubs. The
 * controller is intentionally a thin HTTP adapter — all business logic lives in
 * the service layer, which has its own test classes. Here we verify only the
 * adapter behaviour: return codes, error wrapping, and delegation.
 */
class StorageGatewayControllerTest {

    private StorageGatewayController controller;
    private StubPut put;
    private StubGet getSvc;
    private StubDelete del;

    @BeforeEach
    void setUp() throws Exception {
        controller = new StorageGatewayController();
        put = new StubPut();
        getSvc = new StubGet();
        del = new StubDelete();
        inject(controller, "putObjectService", put);
        inject(controller, "getObjectService", getSvc);
        inject(controller, "deleteObjectService", del);
    }

    @Test
    void putDelegatesToPutObjectServiceAndReturnsOk() {
        ResponseEntity<String> resp = controller.putObject("b", "k",
                new InMemoryMultipartFile("hello".getBytes()));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(put.lastBucket).isEqualTo("b");
        assertThat(put.lastKey).isEqualTo("k");
    }

    @Test
    void putWrapsServiceExceptionAsInternalError() {
        put.throwOnNextCall = new RuntimeException("boom");
        ResponseEntity<String> resp = controller.putObject("b", "k",
                new InMemoryMultipartFile(new byte[0]));
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void getReturnsBytesWhenServiceReturnsValidResult() {
        byte[] payload = "data".getBytes();
        getSvc.nextResult = new GetObjectService.GetResult(payload, false, true);

        ResponseEntity<?> resp = controller.getObject("b", "k");

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((byte[]) resp.getBody()).isEqualTo(payload);
    }

    @Test
    void getReturns404WhenServiceReturnsNull() {
        getSvc.nextResult = null;
        ResponseEntity<?> resp = controller.getObject("b", "missing");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getReturns409OnChecksumMismatch() {
        getSvc.nextResult = new GetObjectService.GetResult("x".getBytes(), false, false);
        ResponseEntity<?> resp = controller.getObject("b", "k");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void deleteReturnsOkWhenServiceFindsAndDeletesObject() {
        del.nextResult = true;
        ResponseEntity<String> resp = controller.deleteObject("b", "k");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void deleteReturns404WhenObjectMissing() {
        del.nextResult = false;
        ResponseEntity<String> resp = controller.deleteObject("b", "k");
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private static void inject(Object t, String f, Object v) throws Exception {
        Field field = t.getClass().getDeclaredField(f);
        field.setAccessible(true);
        field.set(t, v);
    }

    // ── Stubs ──────────────────────────────────────────────────────────────

    static final class StubPut extends PutObjectService {
        String lastBucket, lastKey;
        RuntimeException throwOnNextCall;
        @Override public String putObject(String b, String k, byte[] payload) {
            if (throwOnNextCall != null) {
                RuntimeException ex = throwOnNextCall;
                throwOnNextCall = null;
                throw ex;
            }
            lastBucket = b; lastKey = k;
            return "Stored " + b + "/" + k;
        }
    }

    static final class StubGet extends GetObjectService {
        GetResult nextResult;
        @Override public GetResult getObject(String b, String k) { return nextResult; }
    }

    static final class StubDelete extends DeleteObjectService {
        boolean nextResult;
        @Override public boolean deleteObject(String b, String k) { return nextResult; }
    }

    static final class InMemoryMultipartFile implements MultipartFile {
        private final byte[] bytes;
        InMemoryMultipartFile(byte[] b) { this.bytes = b; }
        @Override public String getName() { return "file"; }
        @Override public String getOriginalFilename() { return "file.bin"; }
        @Override public String getContentType() { return "application/octet-stream"; }
        @Override public boolean isEmpty() { return bytes.length == 0; }
        @Override public long getSize() { return bytes.length; }
        @Override public byte[] getBytes() { return bytes; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
        @Override public void transferTo(File dest) throws IOException { Files.write(dest.toPath(), bytes); }
    }
}
