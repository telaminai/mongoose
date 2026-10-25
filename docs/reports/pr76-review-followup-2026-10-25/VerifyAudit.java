import com.telamin.mongoose.control.ControlAuditExport;
import com.telamin.mongoose.control.validation.ControlEvidence;
import com.telamin.mongoose.control.validation.ControlInvariants;
import java.nio.file.*;
import java.util.*;
import org.yaml.snakeyaml.Yaml;

/** Run from the qualified checkout after mvn test, with SnakeYAML 2.5 on the classpath. */
class VerifyAudit {
    public static void main(String[] args) throws Exception {
        List<Map<String, Object>> reports = new ArrayList<>();
        boolean failed = false;
        for (String arg : args) {
            if (arg.endsWith(".manifest.yaml")) continue;
            Path audit = Path.of(arg);
            var completeness = ControlAuditExport.verify(audit);
            var evidence = ControlEvidence.parse(Files.readString(audit), completeness.permitsInvariantPass());
            var result = ControlInvariants.check(evidence);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("path", arg);
            row.put("completeness", completeness.completeness().name());
            row.put("records", evidence.records().size());
            row.put("verified", result.verified());
            row.put("violations", result.violations());
            row.put("unverifiable", result.unverifiable());
            reports.add(row);
            failed |= !result.verified();
        }
        System.out.print(new Yaml().dump(reports));
        if (failed) System.exit(1);
    }
}
