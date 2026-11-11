package com.robat2.mobile;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

public class ProjectEngineeringTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private File filesDir() throws IOException {
        File root=temp.newFolder("app-files");
        return root;
    }

    private byte[] zip(String[] names, String[] values) throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(ZipOutputStream out=new ZipOutputStream(bytes)) {
            for(int i=0;i<names.length;i++) {
                out.putNextEntry(new ZipEntry(names[i]));
                out.write(values[i].getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    @Test public void zipImportRejectsTraversalAndKeepsAllowedSource() throws Exception {
        File files=filesDir();
        byte[] archive=zip(
            new String[]{"../escape.java","src/Main.java","notes.exe"},
            new String[]{"bad","class Main {}","binary"});
        String result=WorkspaceManager.importZip(new ByteArrayInputStream(archive),files);
        File safe=new File(files,"robat2-workspace/src/Main.java");
        assertTrue(result.contains("1 فایل وارد شد"));
        assertTrue(safe.isFile());
        assertFalse(new File(files,"escape.java").exists());
        assertFalse(new File(files,"robat2-workspace/notes.exe").exists());
    }

    @Test public void generatedFilesRejectTraversalAndSaveSafeFiles() throws Exception {
        File files=filesDir();
        String answer="<ROB2_FILE path=\"../outside.java\">bad</ROB2_FILE>\n"+
            "<ROB2_FILE path=\"src/Safe.java\">class Safe {}</ROB2_FILE>";
        String result=WorkspaceManager.applyGenerated(answer,files);
        assertTrue(result.contains("1 فایل"));
        assertTrue(new File(files,"robat2-workspace/src/Safe.java").isFile());
        assertFalse(new File(files,"outside.java").exists());
    }

    @Test public void overwrittenWorkspaceFileCanBeRestored() throws Exception {
        File files=filesDir();
        WorkspaceManager.applyGenerated(
            "<ROB2_FILE path=\"src/Keep.java\">original</ROB2_FILE>",files);
        WorkspaceManager.applyGenerated(
            "<ROB2_FILE path=\"src/Keep.java\">changed</ROB2_FILE>",files);
        File target=new File(files,"robat2-workspace/src/Keep.java");
        assertEquals("changed",read(target));
        String result=WorkspaceManager.restoreBackups(files);
        assertTrue(result.contains("1 فایل"));
        assertEquals("original",read(target));
    }

    @Test public void analyzerFindsBuildTestsTodoAndPossibleSecret() throws Exception {
        File files=filesDir();
        File root=new File(files,"robat2-workspace");
        assertTrue(root.mkdirs());
        write(new File(root,"build.gradle"),"plugins {}");
        write(new File(root,"src/Main.java"),"// TODO: improve\nclass Main {}");
        write(new File(root,"src/test/MainTest.java"),"class MainTest {}");
        write(new File(root,"config.properties"),"api_key = \"this-is-a-test-secret-value\"");
        String report=ProjectAnalyzer.analyze(files);
        assertTrue(report.contains("build.gradle"));
        assertTrue(report.contains("آزمون‌ها: 1"));
        assertTrue(report.contains("TODO"));
        assertTrue(report.contains("الگوی احتمالی کلید/رمز"));
    }

    private static void write(File file,String value) throws IOException {
        File parent=file.getParentFile();
        if(parent!=null) parent.mkdirs();
        try(OutputStream out=new FileOutputStream(file)) {
            out.write(value.getBytes(StandardCharsets.UTF_8));
        }
    }
    private static String read(File file) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(InputStream in=new FileInputStream(file)) {
            byte[] b=new byte[1024]; int n;
            while((n=in.read(b))!=-1) out.write(b,0,n);
        }
        return new String(out.toByteArray(),StandardCharsets.UTF_8);
    }
}
