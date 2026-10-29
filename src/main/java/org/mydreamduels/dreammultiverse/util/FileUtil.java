package org.mydreamduels.dreammultiverse.util;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;

public final class FileUtil {

    /** Never copied when cloning a world - they identify the original. */
    private static final Set<String> CLONE_SKIP = Set.of("uid.dat", "session.lock");

    private FileUtil() {
    }

    public static void deleteRecursively(File dir) throws IOException {
        if (!dir.exists()) return;
        Files.walkFileTree(dir.toPath(), new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path d, IOException exc) throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public static void copyWorld(File from, File to) throws IOException {
        Path src = from.toPath();
        Path dst = to.toPath();
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path d, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(dst.resolve(src.relativize(d)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (!CLONE_SKIP.contains(file.getFileName().toString())) {
                    Files.copy(file, dst.resolve(src.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** Write via a temp file + move so a crash mid-write never leaves a half-written YAML behind. */
    public static void writeAtomically(File file, String content) throws IOException {
        File parent = file.getParentFile();
        if (parent != null) Files.createDirectories(parent.toPath());
        Path tmp = new File(parent, file.getName() + ".tmp").toPath();
        Files.writeString(tmp, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try {
            Files.move(tmp, file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Files.move(tmp, file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static boolean isInside(File child, File parent) {
        try {
            return child.getCanonicalFile().toPath().startsWith(parent.getCanonicalFile().toPath());
        } catch (IOException e) {
            return false;
        }
    }
}
