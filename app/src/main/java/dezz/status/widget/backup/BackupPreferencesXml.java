/* SPDX-License-Identifier: GPL-3.0-or-later */
package dezz.status.widget.backup;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.*;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.*;
import org.json.*;

/** Lossless preference types, including long/int distinction and every float bit pattern. */
public final class BackupPreferencesXml {
    private BackupPreferencesXml() {}
    public static LinkedHashMap<String,Object> read(File source) throws Exception {
        byte[] bytes=BackupFiles.read(source,16*1024*1024);
        String xml=new String(bytes,StandardCharsets.UTF_8);
        if(xml.contains("<!DOCTYPE")||xml.contains("<!ENTITY"))throw new IOException("External XML entities are forbidden");
        DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance();
        factory.setExpandEntityReferences(false);
        try {factory.setXIncludeAware(false);}catch(UnsupportedOperationException ignored){}
        Document document=factory.newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
        Element map=document.getDocumentElement();
        if(!"map".equals(map.getTagName()))throw new IOException("Invalid preference root");
        LinkedHashMap<String,Object> values=new LinkedHashMap<>();
        for(Node node=map.getFirstChild();node!=null;node=node.getNextSibling()) {
            if(node.getNodeType()!=Node.ELEMENT_NODE)continue;
            Element item=(Element)node;
            if(!item.hasAttribute("name"))throw new IOException("Preference without name");
            String name=item.getAttribute("name");
            if(values.containsKey(name)||values.size()>100000)throw new IOException("Duplicate/too many preference keys");
            Object value;
            switch(item.getTagName()) {
                case "string": value=item.getTextContent();break;
                case "boolean":
                    String bool=item.getAttribute("value");
                    if(!bool.equals("true")&&!bool.equals("false"))throw new IOException("Invalid boolean preference");
                    value=Boolean.parseBoolean(bool);break;
                case "int": value=Integer.valueOf(item.getAttribute("value"));break;
                case "long": value=Long.valueOf(item.getAttribute("value"));break;
                case "float": value=Float.valueOf(item.getAttribute("value"));break;
                case "set":
                    Set<String> set=new LinkedHashSet<>();
                    for(Node member=item.getFirstChild();member!=null;member=member.getNextSibling()) {
                        if(member.getNodeType()!=Node.ELEMENT_NODE)continue;
                        if(!member.getNodeName().equals("string")||!set.add(member.getTextContent()))
                            throw new IOException("Invalid string-set preference");
                    }
                    value=set;break;
                default: throw new IOException("Unknown preference type: "+item.getTagName());
            }
            values.put(name,value);
        }
        return values;
    }

    public static JSONArray encode(Map<String,?> values) throws Exception {
        JSONArray entries=new JSONArray();
        for(Map.Entry<String,?> entry:values.entrySet()) {
            Object value=entry.getValue();JSONObject record=new JSONObject().put("key",entry.getKey());
            if(value instanceof Boolean)record.put("type","boolean").put("value",value);
            else if(value instanceof Integer)record.put("type","int32").put("value",value);
            else if(value instanceof Long)record.put("type","int64").put("value",value.toString());
            else if(value instanceof Float)record.put("type","float32").put("value",Integer.toHexString(Float.floatToRawIntBits((Float)value)));
            else if(value instanceof String)record.put("type","string").put("value",value);
            else if(value instanceof Set) {
                JSONArray set=new JSONArray();for(Object member:(Set<?>)value) {
                    if(!(member instanceof String))throw new IOException("Unknown set member type");set.put(member);
                }
                record.put("type","stringSet").put("value",set);
            } else throw new IOException("Unknown preference value type");
            entries.put(record);
        }
        return entries;
    }

    public static void write(File destination,Map<String,?> values)throws Exception {
        Document document=DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();
        Element root=document.createElement("map");document.appendChild(root);
        for(Map.Entry<String,?> entry:values.entrySet()) {
            Object value=entry.getValue();String type;
            if(value instanceof String)type="string";
            else if(value instanceof Boolean)type="boolean";
            else if(value instanceof Integer)type="int";
            else if(value instanceof Long)type="long";
            else if(value instanceof Float)type="float";
            else if(value instanceof Set)type="set";
            else throw new IOException("Unknown preference value type");
            Element item=document.createElement(type);item.setAttribute("name",entry.getKey());root.appendChild(item);
            if(value instanceof String)item.appendChild(document.createTextNode((String)value));
            else if(value instanceof Set)for(Object member:(Set<?>)value) {
                if(!(member instanceof String))throw new IOException("Unknown set member");
                Element child=document.createElement("string");child.appendChild(document.createTextNode((String)member));item.appendChild(child);
            } else item.setAttribute("value",String.valueOf(value));
        }
        Transformer transformer=TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.ENCODING,"UTF-8");
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        transformer.transform(new DOMSource(document),new StreamResult(output));
        BackupFiles.atomicWrite(destination,output.toByteArray());
        if(!encode(values).toString().equals(encode(read(destination)).toString()))
            throw new IOException("Preference type/value read-back mismatch");
    }

    public static Map<String,Object> decode(JSONArray entries)throws Exception {
        Map<String,Object> values=new LinkedHashMap<>();
        if(entries.length()>100000)throw new IOException("Too many preference values");
        for(int i=0;i<entries.length();i++) {
            JSONObject item=entries.getJSONObject(i);String key=item.getString("key");Object value;
            switch(item.getString("type")) {
                case "boolean": value=item.getBoolean("value");break;
                case "int32": value=item.getInt("value");break;
                case "int64": value=Long.parseLong(item.getString("value"));break;
                case "float32": value=Float.intBitsToFloat((int)Long.parseLong(item.getString("value"),16));break;
                case "string": value=item.getString("value");break;
                case "stringSet":
                    Set<String> set=new LinkedHashSet<>();JSONArray members=item.getJSONArray("value");
                    for(int n=0;n<members.length();n++)if(!set.add(members.getString(n)))throw new IOException("Duplicate set item");
                    value=set;break;
                default: throw new IOException("Unknown preference type");
            }
            if(values.put(key,value)!=null)throw new IOException("Duplicate preference key");
        }
        return values;
    }
}
