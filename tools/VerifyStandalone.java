import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;

/** Verifies optimized bytecode against the Minecraft runtime classpath without starting the game. */
class VerifyStandalone {
    public static void main(String[] args) throws Exception {
        Path archive = Path.of(args[0]);
        List<String> mapping = Files.readAllLines(Path.of(args[1]));
        try (ZipFile jar = new ZipFile(archive.toFile())) {
            for (String entry : List.of("fabric.mod.json", "mixins.pathadol.json")) {
                if (jar.getEntry(entry) == null) {
                    throw new IllegalStateException("Missing " + entry + " from " + archive);
                }
            }
            for (String source : List.of(
                    "baritone.Baritone", "baritone.launch.mixins.MixinMinecraft",
                    "baritone.behavior.PathingBehavior", "baritone.human.BridgeController",
                    "baritone.human.AutoTestRunner", "baritone.process.CombatProcess",
                    "baritone.process.SpeedrunProcess", "baritone.bedwars.BedwarsProcess",
                    "baritone.brain.BrainGymBridge")) {
                String prefix = source + " -> ";
                String target = mapping.stream().filter(line -> line.startsWith(prefix)).findFirst()
                        .map(line -> line.substring(prefix.length(), line.length() - 1)).orElse(source);
                if (jar.getEntry(target.replace('.', '/') + ".class") == null) {
                    throw new IllegalStateException("Missing class " + source + " from " + archive);
                }
                Class.forName(target, false, VerifyStandalone.class.getClassLoader()).getDeclaredMethods();
                System.out.println("Verified " + source);
            }
        }
    }
}
