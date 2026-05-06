package de.firetail.compat.movebank.mirror.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.firetail.compat.movebank.api.client.LicenseChecker;
import de.firetail.compat.movebank.api.client.MovebankApiClient;
import de.firetail.compat.movebank.mirror.MovebankMirror;
import de.firetail.compat.movebank.mirror.Study;
import de.firetail.compat.movebank.mirror.StudyId;
import de.firetail.compat.movebank.mirror.StudyJson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParentCommand;

import java.io.File;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

@Command(name = "metadata",
        mixinStandardHelpOptions = true,
        description = "Pull study metadata for accessible studies. One JSON file per study, "
                + "named %%012d.json under --mirror-dir.")
public class MetadataCommand implements Callable<Integer> {

    private static final Logger logger = LoggerFactory.getLogger(MetadataCommand.class);

    @ParentCommand CliMain parent;

    /** Lets {@link SyncCommand} inject globals when invoking us programmatically. */
    GlobalOptions globalsOverride;

    GlobalOptions globals() {
        return globalsOverride != null ? globalsOverride : parent.globals;
    }

    @Option(names = "--study", paramLabel = "ID", description = "Restrict to specific study ids. Repeatable.")
    Set<String> includeStudies = new HashSet<>();

    @Option(names = "--exclude", paramLabel = "ID", description = "Skip these study ids. Repeatable.")
    Set<String> excludeStudies = new HashSet<>();

    enum LicenseMode { auto, record, reject }

    @Option(names = "--license", paramLabel = "MODE",
            description = "License acceptance: ${COMPLETION-CANDIDATES}. Default: ${DEFAULT-VALUE}. "
                    + "'record' writes <studyId>-license.json alongside the metadata.")
    LicenseMode licenseMode = LicenseMode.record;

    @Option(names = "--dry-run",
            description = "List studies that would be fetched, don't download.")
    boolean dryRun;

    @Option(names = "--list",
            description = "Print accessible study ids and names, then exit. Implies --dry-run.")
    boolean listOnly;

    @Override
    public Integer call() throws Exception {
        GlobalOptions g = globals();
        g.applyLogLevel();

        if (g.user == null || g.user.isBlank()) {
            System.err.println("error: --user (or env MOVEBANK_USER) is required");
            return ExitCode.USAGE_ERROR;
        }
        String password = g.resolvePassword();
        if (!listOnly && !dryRun && (password == null || password.isBlank())) {
            System.err.println("error: password not set; use --password / --password-file / "
                    + "--password-stdin / env MOVEBANK_PASSWORD");
            return ExitCode.USAGE_ERROR;
        }

        File baseDir = g.mirrorDir.toFile();
        if (!baseDir.exists() && !baseDir.mkdirs()) {
            System.err.println("error: could not create mirror dir " + baseDir);
            return ExitCode.IO_ERROR;
        }

        RecordingLicenseChecker recorder = (licenseMode == LicenseMode.record)
                ? new RecordingLicenseChecker(baseDir) : null;
        LicenseChecker checker = switch (licenseMode) {
            case auto   -> html -> true;
            case record -> recorder;
            case reject -> html -> false;
        };

        MovebankApiClient client = new MovebankApiClient(g.baseUrl, g.user, password, checker);
        MovebankMirror mirror = new MovebankMirror(client);

        List<StudyId> all = mirror.getAllStudyIds();
        logger.info("Discovered {} accessible studies", all.size());

        if (listOnly) {
            for (StudyId s : all) {
                System.out.println(s.studyId() + "\t" + s.studyName());
            }
            return ExitCode.OK;
        }

        ObjectMapper mapper = new ObjectMapper();
        int written = 0, errors = 0, skipped = 0;
        for (StudyId studyId : all) {
            if (!includeStudies.isEmpty() && !includeStudies.contains(studyId.studyId())) {
                skipped++;
                continue;
            }
            if (excludeStudies.contains(studyId.studyId())) {
                skipped++;
                continue;
            }
            if (dryRun) {
                System.out.println("[dry-run] " + studyId.studyId() + "\t" + studyId.studyName());
                continue;
            }
            if (recorder != null) {
                recorder.setCurrentStudy(studyId.studyId(), studyId.studyName());
            }
            try {
                Study study = mirror.getStudyRefData(studyId);
                StudyJson json = new StudyJson(study);
                File out = new File(baseDir,
                        String.format("%012d.json", Long.parseLong(studyId.studyId())));
                mapper.writerWithDefaultPrettyPrinter().writeValue(out, json);
                written++;
            } catch (Exception e) {
                logger.error("Failed for study {} ({}): {}",
                        studyId.studyId(), studyId.studyName(), e.getMessage());
                errors++;
            }
        }

        logger.info("Metadata pass complete — wrote {}, skipped {}, failed {}", written, skipped, errors);
        return errors > 0 ? ExitCode.GENERIC_ERROR : ExitCode.OK;
    }
}
