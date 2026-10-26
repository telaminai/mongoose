package com.telamin.mongoose.review77ext;

import org.junit.jupiter.api.Test;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

public class BuilderDisclosureTest {
    static class Fields {
        private String privateValue="private-fixture";
        transient String transientValue="transient-fixture";
        static String staticValue="static-fixture";
    }
    @Test void builderCaptureIncludesPrivateValuesButNotLiterallyAllFields() throws Exception {
        // Builder 1.0.80 declares builder-api 1.1.3. No code generation or network call.
        Path api=Path.of(System.getProperty("user.home"),".m2/repository/com/telamin/fluxtion/fluxtion-builder-api/1.1.3/fluxtion-builder-api-1.1.3.jar");
        assertTrue(java.nio.file.Files.isRegularFile(api),"review prerequisite: published builder-api 1.1.3 cached");
        try(var loader=new URLClassLoader(new java.net.URL[]{api.toUri().toURL()},getClass().getClassLoader())) {
            var capture=loader.loadClass("com.telamin.fluxtion.builder.meta.model.FieldValueCapture");
            var fields=(List<?>)capture.getMethod("capture",Object.class).invoke(null,new Fields());
            assertEquals(1,fields.size());
            var value=fields.getFirst();
            assertEquals("privateValue",value.getClass().getMethod("name").invoke(value));
            assertEquals("private-fixture",value.getClass().getMethod("literal").invoke(value));
        }
    }
}

