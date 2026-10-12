package project;

import java.util.Arrays;

/** Entry point: any CLI option -> command line, otherwise the GUI. */
public class Launcher {

    public static void main(String[] args) {
        boolean cli = Arrays.stream(args).anyMatch(a -> Arrays.asList(CmdLineRunner.OPTIONS).contains(a));
        if (cli) {
            CmdLineRunner.run(args);
        } else {
            OutpostGui.main(args);
        }
    }
}
