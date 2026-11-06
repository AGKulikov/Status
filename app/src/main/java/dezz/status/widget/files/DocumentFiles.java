/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.files;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.*;
import java.util.*;

/** Storage Access Framework adapter for USB/SD providers that disallow direct file access. */
public final class DocumentFiles implements FileBackend {
    private final ContentResolver resolver;
    private final Uri tree;
    public DocumentFiles(ContentResolver resolver,Uri tree){this.resolver=resolver;this.tree=tree;}
    @Override public String identity(){return "tree:"+tree;}
    @Override public boolean writable(){return true;} // Per-document grants are enforced by the provider.
    private Uri document(String path)throws Exception {
        FilePaths.normalize(path);
        Uri current=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));
        if(!path.equals("/"))for(String name:path.substring(1).split("/")) {
            Uri found=null;
            for(Row row:children(current))if(row.name.equals(name)) {
                if(found!=null)throw new IOException("Провайдер вернул неоднозначное имя");
                found=row.uri;
            }
            if(found==null)return null;current=found;
        }
        return current;
    }
    public Uri uri(String path)throws Exception {
        Uri uri=document(path);if(uri==null)throw new FileNotFoundException();return uri;
    }
    private static final class Row {
        Uri uri;String name;boolean directory;long size,modified;
    }
    private List<Row> rows(Uri query)throws IOException {
        List<Row> result=new ArrayList<>();
        String[] columns={DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_LAST_MODIFIED};
        try(Cursor cursor=resolver.query(query,columns,null,null,null)) {
            if(cursor==null)throw new IOException("Накопитель недоступен");
            while(cursor.moveToNext()) {
                Row row=new Row();row.uri=DocumentsContract.buildDocumentUriUsingTree(tree,cursor.getString(0));
                row.name=cursor.getString(1);FilePaths.name(row.name);
                row.directory=DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2));
                row.size=cursor.isNull(3)?-1:cursor.getLong(3);row.modified=cursor.getLong(4);result.add(row);
            }
        }
        return result;
    }
    private List<Row> children(Uri parent)throws IOException {
        return rows(DocumentsContract.buildChildDocumentsUriUsingTree(tree,DocumentsContract.getDocumentId(parent)));
    }
    private FileEntry entry(String path,Row row){return new FileEntry(path,row.name,row.directory,row.size,row.modified);}
    @Override public List<FileEntry> list(String path)throws Exception {
        Uri uri=uri(path);List<FileEntry> result=new ArrayList<>();
        for(Row row:children(uri))result.add(entry(FilePaths.child(path,row.name),row));return result;
    }
    @Override public FileEntry stat(String path)throws Exception {
        Uri uri=document(path);if(uri==null)return null;
        List<Row> values=rows(uri);return values.isEmpty()?null:entry(path,values.get(0));
    }
    @Override public InputStream read(String path)throws Exception {
        InputStream stream=resolver.openInputStream(uri(path));if(stream==null)throw new IOException("Не удалось открыть файл");return stream;
    }
    @Override public void mkdir(String path)throws Exception {
        if(stat(path)!=null)throw new IOException("Папка уже существует");
        Uri made=DocumentsContract.createDocument(resolver,uri(FilePaths.parent(path)),DocumentsContract.Document.MIME_TYPE_DIR,FilePaths.leaf(path));
        if(made==null)throw new IOException("Не удалось создать папку");
    }
    @Override public void write(String path,InputStream input,long length,FileOperations.Control control)throws Exception {
        if(stat(path)!=null)throw new IOException("Файл уже существует");
        Uri stage=DocumentsContract.createDocument(resolver,uri(FilePaths.parent(path)),"application/octet-stream",".natro-"+UUID.randomUUID()+".part");
        if(stage==null)throw new IOException("Нельзя записать файл на накопитель");
        boolean committed=false;
        try {
            try(OutputStream output=resolver.openOutputStream(stage,"wt")) {
                if(output==null)throw new IOException("Нельзя открыть файл для записи");
                long count=FileOperations.pipe(input,output,control);
                if(length>=0&&count!=length)throw new IOException("Неполная передача");
            }
            control.check();if(stat(path)!=null)throw new IOException("В назначении появился одноимённый файл");
            Uri renamed=DocumentsContract.renameDocument(resolver,stage,FilePaths.leaf(path));
            if(renamed==null)throw new IOException("Провайдер не поддерживает завершение записи");
            stage=renamed;
            List<Row> check=rows(stage);
            if(check.size()!=1||!check.get(0).name.equals(FilePaths.leaf(path)))throw new IOException("Провайдер изменил имя файла");
            committed=true;
        } finally {if(!committed)DocumentsContract.deleteDocument(resolver,stage);}
    }
    @Override public void delete(String path)throws Exception {
        if(path.equals("/"))throw new IOException("Нельзя удалить корень накопителя");
        if(!DocumentsContract.deleteDocument(resolver,uri(path)))throw new IOException("Не удалось удалить файл");
    }
}
