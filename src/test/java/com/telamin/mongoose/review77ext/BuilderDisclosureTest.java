package com.telamin.mongoose.review77ext;

import org.junit.jupiter.api.Test;
import com.telamin.fluxtion.builder.meta.model.FieldValueCapture;
import static org.junit.jupiter.api.Assertions.*;

public class BuilderDisclosureTest {
    static class Fields {
        private String privateValue="private-fixture";
        transient String transientValue="transient-fixture";
        static String staticValue="static-fixture";
    }
    @Test void builderCaptureIncludesPrivateValuesButNotLiterallyAllFields() throws Exception {
        // Builder 1.0.80 declares builder-api 1.1.3. No code generation or network call.
        var fields = FieldValueCapture.capture(new Fields());
        assertEquals(1, fields.size());
        var value = fields.getFirst();
        assertEquals("privateValue", value.name());
        assertEquals("private-fixture", value.literal());
    }
}
