package org.qortal.arbitrary;

import com.github.difflib.DiffUtils;
import com.github.difflib.UnifiedDiffUtils;
import com.github.difflib.patch.Patch;
import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.qortal.arbitrary.metadata.ArbitraryDataMetadataPatch;
import org.qortal.crypto.Crypto;
import org.qortal.settings.Settings;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

public class ArbitraryDataDiff {

    private static final Logger LOGGER = LogManager.getLogger(ArbitraryDataDiff.class);

    private Path pathBefore;
    private Path pathAfter;
    private byte[] previousSignature;
    private byte[] previousHash;
    private Path diffPath;
    private String identifier;

    private List<Path> addedPaths;
    private List<Path> modifiedPaths;
    private List<Path> removedPaths;

    public ArbitraryDataDiff(Path pathBefore, Path pathAfter, byte[] previousSignature) {
        this.pathBefore = pathBefore;
        this.pathAfter = pathAfter;
        this.previousSignature = previousSignature;

        this.addedPaths = new ArrayList<>();
        this.modifiedPaths = new ArrayList<>();
        this.removedPaths = new ArrayList<>();

        this.createRandomIdentifier();
        this.createOutputDirectory();
    }

    public void compute() throws IOException {
        try {
            this.preExecute();
            this.hashPreviousState();
            this.findAddedOrModifiedFiles();
            this.findRemovedFiles();
            this.validate();
            this.writeMetadata();

        } finally {
            this.postExecute();
        }
    }

    private void preExecute() {
        LOGGER.info("Generating diff...");
    }

    private void postExecute() {

    }

    private void createRandomIdentifier() {
        this.identifier = UUID.randomUUID().toString();
    }

    private void createOutputDirectory() {
        // Use the user-specified temp dir, as it is deterministic, and is more likely to be located on reusable storage hardware
        String baseDir = Settings.getInstance().getTempDataPath();
        Path tempDir = Paths.get(baseDir, "diff", this.identifier);
        try {
            Files.createDirectories(tempDir);
        } catch (IOException e) {
            throw new IllegalStateException("Unable to create temp directory");
        }
        this.diffPath = tempDir;
    }

    private void hashPreviousState() throws IOException {
        ArbitraryDataDigest digest = new ArbitraryDataDigest(this.pathBefore);
        digest.compute();
        this.previousHash = digest.getHash();
    }

    private void findAddedOrModifiedFiles() {
        try {
            final Path pathBeforeAbsolute = this.pathBefore.toAbsolutePath();
            final Path pathAfterAbsolute = this.pathAfter.toAbsolutePath();
            final Path diffPathAbsolute = this.diffPath.toAbsolutePath();
            final ArbitraryDataDiff diff = this;

            // Check for additions or modifications
            Files.walkFileTree(this.pathAfter, new FileVisitor<Path>() {

                @Override
                public FileVisitResult preVisitDirectory(Path after, BasicFileAttributes attrs) {
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path after, BasicFileAttributes attrs) throws IOException {
                    Path filePathAfter = pathAfterAbsolute.relativize(after.toAbsolutePath());
                    Path filePathBefore = pathBeforeAbsolute.resolve(filePathAfter);

                    if (filePathAfter.startsWith(".qortal")) {
                        // Ignore the .qortal metadata folder
                        return FileVisitResult.CONTINUE;
                    }

                    boolean wasAdded = false;
                    boolean wasModified = false;

                    if (!Files.exists(filePathBefore)) {
                        LOGGER.info("File was added: {}", filePathAfter.toString());
                        diff.addedPaths.add(filePathAfter);
                        wasAdded = true;
                    }
                    else if (Files.size(after) != Files.size(filePathBefore)) {
                        // Check file size first because it's quicker
                        LOGGER.info("File size was modified: {}", filePathAfter.toString());
                        diff.modifiedPaths.add(filePathAfter);
                        wasModified = true;
                    }
                    else if (!Arrays.equals(ArbitraryDataDiff.digestFromPath(after), ArbitraryDataDiff.digestFromPath(filePathBefore))) {
                        // Check hashes as a last resort
                        LOGGER.info("File contents were modified: {}", filePathAfter.toString());
                        diff.modifiedPaths.add(filePathAfter);
                        wasModified = true;
                    }

                    if (wasAdded) {
                        ArbitraryDataDiff.copyFilePathToBaseDir(after, diffPathAbsolute, filePathAfter);
                    }
                    if (wasModified) {
                        // Create patch using java-diff-utils
                        Path destination = Paths.get(diffPathAbsolute.toString(), filePathAfter.toString());
                        ArbitraryDataDiff.createAndCopyDiffUtilsPatch(filePathBefore, after, destination);
                    }

                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e){
                    LOGGER.info("File visit failed: {}, error: {}", file.toString(), e.getMessage());
                    // TODO: throw exception?
                    return FileVisitResult.TERMINATE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException e) {
                    return FileVisitResult.CONTINUE;
                }

            });
        } catch (IOException e) {
            // TODO: throw exception?
            LOGGER.info("IOException when walking through file tree: {}", e.getMessage());
        }
    }

    private void findRemovedFiles() throws IOException {
        try {
            final Path pathBeforeAbsolute = this.pathBefore.toAbsolutePath();
            final Path pathAfterAbsolute = this.pathAfter.toAbsolutePath();
            final ArbitraryDataDiff diff = this;

            // Check for removals
            Files.walkFileTree(this.pathBefore, new FileVisitor<Path>() {

                @Override
                public FileVisitResult preVisitDirectory(Path before, BasicFileAttributes attrs) throws IOException {
                    Path directoryPathBefore = pathBeforeAbsolute.relativize(before.toAbsolutePath());
                    Path directoryPathAfter = pathAfterAbsolute.resolve(directoryPathBefore);

                    if (directoryPathBefore.startsWith(".qortal")) {
                        // Ignore the .qortal metadata folder
                        return FileVisitResult.CONTINUE;
                    }

                    if (!Files.exists(directoryPathAfter)) {
                        LOGGER.info("Directory was removed: {}", directoryPathAfter.toString());
                        diff.removedPaths.add(directoryPathBefore);
                        // TODO: we might need to mark directories differently to files
                    }

                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path before, BasicFileAttributes attrs) throws IOException {
                    Path filePathBefore = pathBeforeAbsolute.relativize(before.toAbsolutePath());
                    Path filePathAfter = pathAfterAbsolute.resolve(filePathBefore);

                    if (filePathBefore.startsWith(".qortal")) {
                        // Ignore the .qortal metadata folder
                        return FileVisitResult.CONTINUE;
                    }

                    if (!Files.exists(filePathAfter)) {
                        LOGGER.trace("File was removed: {}", filePathBefore.toString());
                        diff.removedPaths.add(filePathBefore);
                    }

                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFileFailed(Path file, IOException e){
                    LOGGER.info("File visit failed: {}, error: {}", file.toString(), e.getMessage());
                    // TODO: throw exception?
                    return FileVisitResult.TERMINATE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException e) {
                    return FileVisitResult.CONTINUE;
                }

            });
        } catch (IOException e) {
            throw new IOException(String.format("IOException when walking through file tree: %s", e.getMessage()));
        }
    }

    private void validate() {
        if (this.addedPaths.isEmpty() && this.modifiedPaths.isEmpty() && this.removedPaths.isEmpty()) {
            throw new IllegalStateException("Current state matches previous state. Nothing to do.");
        }
    }

    private void writeMetadata() throws IOException {
        ArbitraryDataMetadataPatch metadata = new ArbitraryDataMetadataPatch(this.diffPath);
        metadata.setPatchType("unified-diff");
        metadata.setAddedPaths(this.addedPaths);
        metadata.setModifiedPaths(this.modifiedPaths);
        metadata.setRemovedPaths(this.removedPaths);
        metadata.setPreviousSignature(this.previousSignature);
        metadata.setPreviousHash(this.previousHash);
        metadata.write();
    }


    private static byte[] digestFromPath(Path path) {
        try {
            return Crypto.digest(Files.readAllBytes(path));
        } catch (IOException e) {
            return null;
        }
    }

    private static void copyFilePathToBaseDir(Path source, Path base, Path relativePath) throws IOException {
        if (!Files.exists(source)) {
            throw new IOException(String.format("File not found: %s", source.toString()));
        }

        // Ensure parent folders exist in the destination
        Path dest = Paths.get(base.toString(), relativePath.toString());
        File file = new File(dest.toString());
        File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }

        LOGGER.trace("Copying {} to {}", source, dest);
        Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void createAndCopyDiffUtilsPatch(Path before, Path after, Path destination) throws IOException {
        if (!Files.exists(before)) {
            throw new IOException(String.format("File not found (before): %s", before.toString()));
        }
        if (!Files.exists(after)) {
            throw new IOException(String.format("File not found (after): %s", after.toString()));
        }

        // Ensure parent folders exist in the destination
        File file = new File(destination.toString());
        File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }

        // Delete an existing file if it exists
        File destFile = destination.toFile();
        if (destFile.exists() && destFile.isFile()) {
            Files.delete(destination);
        }

        // Load the two files into memory
        List<String> original = FileUtils.readLines(before.toFile(), StandardCharsets.UTF_8);
        List<String> revised = FileUtils.readLines(after.toFile(), StandardCharsets.UTF_8);

        // Generate diff information
        Patch<String> diff = DiffUtils.diff(original, revised);

        // Generate unified diff format
        String originalFileName = before.getFileName().toString();
        String revisedFileName = after.getFileName().toString();
        List<String> unifiedDiff = UnifiedDiffUtils.generateUnifiedDiff(originalFileName, revisedFileName, original, diff, 0);

        // Write the diff to the destination directory
        FileWriter fileWriter = new FileWriter(destination.toString(), true);
        BufferedWriter writer = new BufferedWriter(fileWriter);
        for (String line : unifiedDiff) {
            writer.append(line);
            writer.newLine();
        }
        writer.flush();
        writer.close();
    }
    

    public Path getDiffPath() {
        return this.diffPath;
    }

}
