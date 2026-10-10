package net.pocketmap

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.Files

class ReportPhotoProviderTest {
    @Test fun exposesOnlyExistingReportPhotos() {
        val root = Files.createTempDirectory("report-provider").toFile()
        try {
            val directory = File(root, "report-photos").apply { mkdirs() }
            val photo = File(directory, "Trail-report-123.jpg").apply { writeText("photo") }
            assertEquals(photo.canonicalFile, ReportPhotoProvider.photoFile(root, photo.name))
            for (name in listOf("../private.jpg", "Trail-report-../private.jpg", "private.jpg", "Trail-report-123.gpx", "Trail-report-missing.jpg")) {
                try { ReportPhotoProvider.photoFile(root, name); fail("Accepted $name") }
                catch (_: FileNotFoundException) { }
            }
            val secret = File(root, "private.jpg").apply { writeText("private") }
            Files.createSymbolicLink(File(directory, "Trail-report-link.jpg").toPath(), secret.toPath())
            try { ReportPhotoProvider.photoFile(root, "Trail-report-link.jpg"); fail("Accepted symlink outside photo directory") }
            catch (_: FileNotFoundException) { }
        } finally { root.deleteRecursively() }
    }
}
