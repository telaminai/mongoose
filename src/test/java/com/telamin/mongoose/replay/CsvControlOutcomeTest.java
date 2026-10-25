package com.telamin.mongoose.replay;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class CsvControlOutcomeTest {
 @Test void multilineControlFailuresRoundTripWithoutBreakingPhysicalRows(@TempDir Path dir) throws Exception {
  Path file = dir.resolve("replay.csv");
  var ordinary = new ReplayEntry.ControlCall("stopService", List.of("p"), "ok", 1);
  var failure = new ReplayEntry.ControlCall("startService", List.of("p\nsecond", "quote,\""),
          "failed: first line\nsecond line\r\nquote,\"", 2);
  try(var store = new CsvReplayStore(file, new JavaSerializationCodec())) {
   store.append("p", ordinary); store.append("p", failure);
   assertEquals(List.of(ordinary, failure), store.entries("p"));
  }
  assertEquals(3, Files.readAllLines(file).size());
  assertTrue(Files.readAllLines(file).get(2).startsWith("p,CONTROL64,"), "older readers must reject the new encoding rather than treat a failure as success");
  assertTrue(Files.readAllLines(file).get(1).startsWith("p,CONTROL,stopService,ok,,"), "legacy ordinary encoding retained");
  try(var store = new CsvReplayStore(file, new JavaSerializationCodec())) {
   assertEquals(List.of(ordinary, failure), store.entries("p"));
  }
 }
}
