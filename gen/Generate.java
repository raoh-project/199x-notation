import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Regenerates every table in the repository from the Unicode Character Database.
 *
 * <p>Reads the database once into a {@link UcdModel}, which is where every derivation and every
 * check against a published property is made, and writes the model in each language with that
 * language's emitter. Nothing is written until the model is built and every source is rendered, so
 * a check that fails leaves every table as it was.
 *
 * <p>Not part of any build: taking a later Unicode version is a change to the specifications, not a
 * dependency update, so regenerating is a deliberate, separate step. Run from the repository root:
 *
 * <pre>java gen/Generate.java ucd/18.0.0</pre>
 */
public final class Generate {

    private Generate() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("usage: java gen/Generate.java <ucd-directory>");
            System.exit(1);
        }
        UcdModel model = UcdModel.read(Path.of(args[0]));
        Map<Path, String> sources = JavaEmitter.render(model);
        for (Map.Entry<Path, String> source : sources.entrySet()) {
            Files.writeString(source.getKey(), source.getValue(), StandardCharsets.UTF_8);
            System.out.println("wrote " + source.getKey());
        }
    }
}
