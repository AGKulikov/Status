/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.files;

import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.Assert.*;

public class FileOperationsTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private LocalFiles source,target;
    private File from,to,scratch;
    @Before public void setup()throws Exception {from=temporary.newFolder("from");to=temporary.newFolder("to");scratch=temporary.newFolder("scratch");source=new LocalFiles(from);target=new LocalFiles(to);}
    private FileOperations.Control control(){return new FileOperations.Control(null);}
    private void put(File parent,String name,String data)throws Exception {File file=new File(parent,name);file.getParentFile().mkdirs();Files.write(file.toPath(),data.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
    private String read(File parent,String name)throws Exception{return new String(Files.readAllBytes(new File(parent,name).toPath()),java.nio.charset.StandardCharsets.UTF_8);}
    @Test public void recursiveCopyPreservesUnicodeAndEmptyDirectories()throws Exception {
        put(from,"Музыка/один.txt","Песня");new File(from,"Музыка/Пустая").mkdir();
        FileOperations.copy(source,"/Музыка",target,"/","Копия",false,control());
        assertEquals("Песня",read(to,"Копия/один.txt"));assertTrue(new File(to,"Копия/Пустая").isDirectory());assertTrue(new File(from,"Музыка/один.txt").isFile());
    }
    @Test public void collisionKeepsBothFiles()throws Exception {
        put(from,"song.txt","new");put(to,"song.txt","old");
        assertEquals("/song (1).txt",FileOperations.copy(source,"/song.txt",target,"/","song.txt",false,control()));
        assertEquals("old",read(to,"song.txt"));assertEquals("new",read(to,"song (1).txt"));
    }
    @Test public void failedMovePreservesWholeSourceAndExistingDestination()throws Exception {
        put(from,"dir/a","a");put(from,"dir/b","b");
        FileOperations.Control c=new FileOperations.Control((n,b)->{if(b>0)throw new RuntimeException("disconnect");});
        assertThrows(RuntimeException.class,()->FileOperations.copy(source,"/dir",target,"/","dir",true,c));
        assertEquals("a",read(from,"dir/a"));assertEquals("b",read(from,"dir/b"));
        assertEquals(0,Objects.requireNonNull(new File(to,"dir").list()).length);
    }
    @Test public void successfulMoveRemovesSourceOnlyAfterAllBytesCopied()throws Exception {
        put(from,"x","content");FileOperations.copy(source,"/x",target,"/","x",true,control());
        assertFalse(new File(from,"x").exists());assertEquals("content",read(to,"x"));
    }
    @Test public void changedFolderDuringCopyIsNotDeleted()throws Exception {
        put(from,"dir/original","content");
        FileOperations.Control c=new FileOperations.Control((n,b)->{if(b>0)try{put(from,"dir/new","uncopied");}catch(Exception e){throw new RuntimeException(e);}});
        assertThrows(IOException.class,()->FileOperations.copy(source,"/dir",target,"/","dir",true,c));
        assertEquals("uncopied",read(from,"dir/new"));assertEquals("content",read(from,"dir/original"));assertEquals("content",read(to,"dir/original"));
    }
    @Test public void changedFileDuringCopyIsNotDeleted()throws Exception {
        put(from,"original","content");
        FileOperations.Control c=new FileOperations.Control((n,b)->{if(b>0)new File(from,"original").setLastModified(1000);});
        assertThrows(IOException.class,()->FileOperations.copy(source,"/original",target,"/","copy",true,c));
        assertEquals("content",read(from,"original"));assertEquals("content",read(to,"copy"));
    }
    @Test public void cancellationCleansPartialFile()throws Exception {
        FileOperations.Control c=control();c.cancelled=true;
        assertThrows(InterruptedIOException.class,()->target.write("/target",new ByteArrayInputStream(new byte[100]),100,c));
        assertEquals(0,Objects.requireNonNull(to.list()).length);
    }
    @Test public void shortReadNeverCommitsFinalFile()throws Exception {
        assertThrows(IOException.class,()->target.write("/target",new ByteArrayInputStream(new byte[3]),10,control()));
        assertEquals(0,Objects.requireNonNull(to.list()).length);
    }
    @Test public void noOverwriteWhenTargetAppearsDuringTransfer()throws Exception {
        InputStream input=new ByteArrayInputStream(new byte[3]){boolean created;
            @Override public synchronized int read(byte[] b,int off,int len){if(!created){created=true;try{put(to,"target","existing");}catch(Exception e){throw new RuntimeException(e);}}return super.read(b,off,len);}};
        assertThrows(FileAlreadyExistsException.class,()->target.write("/target",input,3,control()));assertEquals("existing",read(to,"target"));
    }
    @Test public void rejectsSelfRecursiveCopyAndRootDelete()throws Exception {
        new File(from,"dir/sub").mkdirs();
        assertThrows(IOException.class,()->FileOperations.copy(source,"/dir",source,"/dir/sub","dir",false,control()));
        assertThrows(IOException.class,()->FileOperations.deleteTree(source,"/",control(),0));
    }
    @Test public void rejectsTraversalAndSymbolicLinks()throws Exception {
        for(String path:Arrays.asList("/../outside","/a/../b","/a//b","/a/","relative","/a\\b"))assertThrows(path,IOException.class,()->source.resolve(path));
        Files.createSymbolicLink(new File(from,"link").toPath(),to.toPath());
        assertThrows(IOException.class,()->source.resolve("/link/x"));assertTrue(source.list("/").isEmpty());
    }
    @Test public void rejectsSelfCopyAcrossDifferentRootAliases()throws Exception {
        new File(from,"dir/sub").mkdirs();
        LocalFiles alias=new LocalFiles(new File(from,"dir/sub"));
        assertThrows(IOException.class,()->FileOperations.copy(source,"/dir",alias,"/","dir",false,control()));
    }
    @Test public void recursiveSearchFindsNestedNamesWithoutCaseSensitivity()throws Exception {
        put(from,"nested/Track.MP3","music");assertEquals("/nested/Track.MP3",FileOperations.search(source,"/","track",control()).get(0).path);
    }
    @Test public void zipRoundTripCreatesDedicatedFolderAndPreservesContents()throws Exception {
        put(from,"dir/файл.txt","данные");new File(from,"dir/empty").mkdir();
        String zip=FileArchives.create(source,source.list("/"),target,"/","archive.zip",scratch,control());
        String result=FileArchives.extract(target,target.stat(zip),target,"/",scratch,control());
        assertEquals("данные",read(to,result.substring(1)+"/dir/файл.txt"));assertTrue(new File(to,"archive/dir/empty").isDirectory());
        assertEquals(0,Objects.requireNonNull(scratch.list()).length);
    }
    @Test public void maliciousZipCannotEscapeDestination()throws Exception {
        try(ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(new File(from,"evil.zip")))){zip.putNextEntry(new ZipEntry("../escaped"));zip.write(1);zip.closeEntry();}
        assertThrows(IOException.class,()->FileArchives.extract(source,source.stat("/evil.zip"),target,"/",scratch,control()));
        assertFalse(new File(to,"escaped").exists());assertFalse(new File(temporary.getRoot(),"escaped").exists());assertEquals(0,Objects.requireNonNull(scratch.list()).length);
    }
}
